package ironmcp

import cats.effect.{IO, Ref}
import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.*
import ironmcp.protocol.*
import ironmcp.schema.{JsonSchemaOf, Titled}
import ironmcp.server.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** The client's side of multi round-trip requests, against an interactive tool. */
class MrtrClientSuite extends CatsEffectSuite:

  final case class Args(room: String :| Not[Empty]) derives Decoder, JsonSchemaOf
  final case class Details(title: String :| (Titled["Meeting title"] & MinLength[1])) derives Decoder, JsonSchemaOf
  final case class Confirm(confirm: Boolean) derives Decoder, JsonSchemaOf

  private given RequestStateCodec = RequestStateCodec.hmac(Array.fill(32)(3.toByte))

  private val book = McpTool.interactive[Args]("book", "Books a room.") { (args, ctx) =>
    val room: String = args.room
    IO.pure(ctx.requestState match
      case None => ctx.ask("details")("details" -> Elicit.form[Details](s"Booking $room."))
      case Some("details") =>
        ctx.accepted[Details]("details") match
          case Some(d) => ctx.ask(s"confirm:${d.title: String}")("confirm" -> Elicit.form[Confirm]("Book it?"))
          case None    => Interaction.done(s"$room not booked.")
      case Some(state) =>
        if ctx.accepted[Confirm]("confirm").exists(_.confirm) then Interaction.done(s"Booked $room for ${state.stripPrefix("confirm:")}.")
        else Interaction.done(s"$room not booked.")
    )
  }

  private val forever = McpTool.interactive[Args]("forever", "Never satisfied.") { (_, ctx) =>
    val again: Interaction[String] = ctx.ask("again")("again" -> Elicit.form[Confirm]("Again?"))
    IO.pure(again)
  }

  private val server = McpServer[IO](info = Implementation(name = "mrtr", version = "0"), tools = Some(ToolSet.of(book, forever)))

  private def accept(content: (String, Json)*) = IO.pure(ElicitResult(ElicitAction.Accept, Some(JsonObject(content*))))

  /** Answers forms in order, recording what it was asked. */
  private def scripted(answers: List[IO[ElicitResult]]) =
    Ref.of[IO, (List[IO[ElicitResult]], List[String])]((answers, Nil)).map { state =>
      val handler: ElicitFormParams => IO[ElicitResult] = params =>
        state.modify { case (next :: rest, asked) => ((rest, asked :+ params.message), next); case other => (other, IO.raiseError(AssertionError("no answer left"))) }.flatten
      (handler, state.get.map(_._2))
    }

  private def text(r: CallToolResult) = r.content.collectFirst { case ContentBlock.Text(t, _, _) => t }.getOrElse("")
  private val room = JsonObject("room" -> "4B".asJson)

  test("the handler declares the elicitation capability"):
    McpClient[IO](ClientTransport.inProcess(server), handlers = InputHandlers.elicitation[IO](_ => accept())).map { c =>
      assert(c.capabilities.elicitation.isDefined)
    }

  test("callToolGathering answers every round and returns the final result"):
    for
      (handler, asked) <- scripted(List(accept("title" -> "Standup".asJson), accept("confirm" -> true.asJson)))
      c                <- McpClient[IO](ClientTransport.inProcess(server), handlers = InputHandlers.elicitation(handler))
      result           <- c.callToolGathering("book", room)
      messages         <- asked
    yield
      assertEquals(text(result), "Booked 4B for Standup.")
      assertEquals(messages, List("Booking 4B.", "Book it?"))

  test("a declined form reaches the tool, which decides what that means"):
    for
      (handler, _) <- scripted(List(IO.pure(ElicitResult(ElicitAction.Decline))))
      c            <- McpClient[IO](ClientTransport.inProcess(server), handlers = InputHandlers.elicitation(handler))
      result       <- c.callToolGathering("book", room)
    yield assertEquals(text(result), "4B not booked.")

  test("the retry budget stops a server that never stops asking"):
    for
      c      <- McpClient[IO](ClientTransport.inProcess(server), handlers = InputHandlers.elicitation[IO](_ => accept("confirm" -> true.asJson)))
      result <- c.callToolGathering("forever", room, maxRounds = 3).attempt
    yield assertEquals(result, Left(McpClientError.RetryBudgetExhausted(3)))

  test("the deadline also cancels a prompt still waiting on the user"):
    for
      cancelled <- Ref.of[IO, Boolean](false)
      c <- McpClient[IO](ClientTransport.inProcess(server),
             handlers = InputHandlers.elicitation[IO](_ => IO.never[ElicitResult].onCancel(cancelled.set(true))))
      result <- c.callToolGathering("book", room, deadline = 200.millis).attempt
      wasCancelled <- cancelled.get
    yield
      assertEquals(result, Left(McpClientError.GatherTimedOut(200.millis)))
      assert(wasCancelled, "the open prompt was cancelled, not left running")

  test("without a handler the client declares no elicitation, so the server answers with a tool error instead of asking"):
    for
      c        <- McpClient[IO](ClientTransport.inProcess(server))
      raw      <- c.callTool("book", room)
      gathered <- c.callToolGathering("book", room).attempt
    yield
      // The server refuses to ask a client that declared no elicitation capability.
      raw match
        case r: CallToolResult => assertEquals(r.isError, Some(true))
        case other             => fail(s"unexpected $other")
      gathered match
        case Right(r) => assertEquals(r.isError, Some(true))
        case Left(e)  => fail(s"unexpected $e")
