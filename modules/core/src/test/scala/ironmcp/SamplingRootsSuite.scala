package ironmcp

import cats.effect.{IO, Ref}
import io.circe.{Decoder, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.transport.Wire
import munit.CatsEffectSuite

/** Sampling and roots requests, asked through `input_required` (multi round-trip requests). */
class SamplingRootsSuite extends CatsEffectSuite:

  final case class Topic(topic: String :| Not[Empty]) derives Decoder, JsonSchemaOf
  final case class NoArgs(dummy: Option[String]) derives Decoder, JsonSchemaOf

  private given RequestStateCodec = RequestStateCodec.hmac(Array.fill(32)(7.toByte))

  private val summarize = McpTool.interactive[Topic]("summarize", "Summarizes a topic with the client's model.") { (args, ctx) =>
    val topic: String = args.topic
    IO.pure(ctx.requestState match
      case None => ctx.ask("sampled")("summary" -> Sample.message(s"Summarize $topic in one line.", maxTokens = 50))
      case Some(_) =>
        ctx.sampledText("summary") match
          case Some(text) => Interaction.done(s"Summary: $text")
          case None       => Interaction.done("The client's model gave no text.")
    )
  }

  private val workspace = McpTool.interactive[NoArgs]("workspace", "Names the client's roots.") { (_, ctx) =>
    IO.pure(ctx.requestState match
      case None => ctx.ask("roots")("roots" -> Roots.list)
      case Some(_) =>
        Interaction.done(ctx.roots("roots").getOrElse(Nil).map(r => r.name.getOrElse(r.uri: String)).mkString(", "))
    )
  }

  private val server = McpServer[IO](Implementation("sampling", "0"), tools = Some(ToolSet.of(summarize, workspace)))

  private def text(r: CallToolResult) = r.content.collectFirst { case ContentBlock.Text(t, _, _) => t }.getOrElse("")

  test("sampling/createMessage and roots/list input requests round-trip through Wire"):
    val sample = InputRequest.sample(CreateMessageParams(List(SamplingMessage(Role.user, ContentBlock.Text("hi"))), 10, includeContext = Some(IncludeContext.thisServer)))
    val json   = parse(Wire.json(sample.asJson)).toOption.get
    assertEquals(json.hcursor.get[String]("method"), Right("sampling/createMessage"))
    assertEquals(json.hcursor.downField("params").get[String]("includeContext"), Right("thisServer"))
    assertEquals(json.hcursor.downField("params").downField("messages").downArray.downField("content").get[String]("type"), Right("text"))
    assertEquals(json.as[InputRequest].map(_.params.as[CreateMessageParams].map(_.maxTokens)), Right(Right(10)))
    val result = parse("""{"role":"assistant","content":{"type":"text","text":"ok"},"model":"qwen","stopReason":"endTurn"}""").toOption.get
    assertEquals(result.as[CreateMessageResult].map(_.model: String), Right("qwen"))
    assertEquals(Wire.json(InputRequest.listRoots.asJson), """{"method":"roots/list","params":{}}""")

  test("handlers declare sampling and roots, and only those given"):
    val both = InputHandlers.sampling[IO](_ => IO.never) ++ InputHandlers.roots[IO](IO.pure(Nil))
    assert(both.capabilities.sampling.isDefined)
    assert(both.capabilities.roots.isDefined)
    assert(both.capabilities.elicitation.isEmpty)
    assert(InputHandlers.roots[IO](IO.pure(Nil)).capabilities.sampling.isEmpty)

  test("a tool samples the client's model and finishes with its answer"):
    for
      asked <- Ref.of[IO, List[CreateMessageParams]](Nil)
      handlers = InputHandlers.sampling[IO](params =>
                   asked.update(_ :+ params).as(CreateMessageResult(Role.assistant, ContentBlock.Text("Cats are liquid."), "scripted"))
                 )
      c      <- McpClient[IO](ClientTransport.inProcess(server), handlers = handlers)
      result <- c.callToolGathering("summarize", JsonObject("topic" -> "cats".asJson))
      seen   <- asked.get
    yield
      assertEquals(text(result), "Summary: Cats are liquid.")
      assertEquals(seen.map(_.maxTokens), List(50))
      assertEquals(seen.flatMap(_.messages).map(_.content), List(ContentBlock.Text("Summarize cats in one line.")))

  test("a tool lists the client's roots"):
    val roots = List(Root("file:///home/dan/iron-mcp", Some("iron-mcp")), Root("file:///tmp"))
    for
      c      <- McpClient[IO](ClientTransport.inProcess(server), handlers = InputHandlers.roots[IO](IO.pure(roots)))
      result <- c.callToolGathering("workspace")
    yield assertEquals(text(result), "iron-mcp, file:///tmp")

  test("a client without a sampling handler gets a tool error instead of a question"):
    for
      c      <- McpClient[IO](ClientTransport.inProcess(server), handlers = InputHandlers.roots[IO](IO.pure(Nil)))
      result <- c.callTool("summarize", JsonObject("topic" -> "cats".asJson))
    yield result match
      case r: CallToolResult =>
        assertEquals(r.isError, Some(true))
        assert(text(r).contains("sampling"), text(r))
      case other => fail(s"unexpected $other")
