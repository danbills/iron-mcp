package ironmcp

import cats.effect.IO
import io.circe.{Decoder, Json, parser}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.schema.{Default, JsonSchemaOf, Titled}
import ironmcp.server.*
import ironmcp.transport.Wire
import munit.CatsEffectSuite

/** Multi round-trip requests, driven over real wire JSON: a tool that asks for a
  * form, then a confirmation, then answers.
  */
class MrtrSuite extends CatsEffectSuite:

  final case class Args(room: String :| Not[Empty]) derives Decoder, JsonSchemaOf

  final case class Details(
      title: String :| ((Titled["Meeting title"] & MinLength[1] & MaxLength[80]) DescribedAs "Shown on the room display."),
      notifyAttendees: Option[Boolean :| (Titled["Notify attendees"] & Default[true])] = None
  ) derives Decoder, JsonSchemaOf

  final case class Confirm(confirm: Boolean) derives Decoder, JsonSchemaOf

  private given RequestStateCodec = RequestStateCodec.hmac(Array.fill(32)(7.toByte))

  private val book = McpTool.interactive[Args]("book_meeting", "Books a room after asking for details.") { (args, ctx) =>
    val room: String = args.room
    IO.pure(ctx.requestState match
      case None => ctx.ask("details")("details" -> Elicit.form[Details](s"Booking $room."))
      case Some("details") =>
        ctx.accepted[Details]("details") match
          case None          => Interaction.done(s"$room not booked.")
          case Some(details) => ctx.ask(s"confirm:${details.title: String}")("confirm" -> Elicit.form[Confirm]("Book it?"))
      case Some(state) =>
        val title = state.stripPrefix("confirm:")
        if ctx.accepted[Confirm]("confirm").exists(_.confirm) then Interaction.done(s"Booked $room for $title.")
        else Interaction.done(s"$room not booked.")
    )
  }

  private val server = McpServer[IO](info = Implementation(name = "mrtr", version = "0.1.0"), tools = Some(ToolSet.of(book)))

  private def meta(elicitation: Boolean): Json =
    RequestMeta(
      protocolVersion = LatestProtocolVersion,
      clientCapabilities = if elicitation then ClientCapabilities(elicitation = Some(ElicitationCapability())) else ClientCapabilities.none
    ).asJson

  private def call(
      state: Option[String] = None,
      responses: Map[String, Json] = Map.empty,
      room: String = "4B",
      elicitation: Boolean = true
  ): IO[Json] =
    val params = Json
      .obj(
        "name"      -> Json.fromString("book_meeting"),
        "arguments" -> Json.obj("room" -> Json.fromString(room)),
        "_meta"     -> meta(elicitation)
      )
      .deepMerge(state.fold(Json.obj())(s => Json.obj("requestState" -> Json.fromString(s))))
      .deepMerge(if responses.isEmpty then Json.obj() else Json.obj("inputResponses" -> responses.asJson))
    val line = s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":${params.noSpaces}}"""
    IO.fromEither(Wire.decode(line).left.map(e => AssertionError(e.toString)))
      .flatMap(server.handle)
      .flatMap(reply => IO.fromEither(parser.parse(Wire.encode(reply.get))))
      .map(_.hcursor.downField("result").focus.get)

  private def accept(content: Json): Json = Json.obj("action" -> Json.fromString("accept"), "content" -> content)
  private def text(result: Json): String  = result.hcursor.downField("content").downArray.get[String]("text").toOption.get
  private def state(result: Json): String = result.hcursor.get[String]("requestState").toOption.get

  test("the first call asks for a form derived from the type, with titles and defaults"):
    call().map { result =>
      assertEquals(result.hcursor.get[String]("resultType"), Right("input_required"))
      val request = result.hcursor.downField("inputRequests").downField("details")
      assertEquals(request.get[String]("method"), Right("elicitation/create"))
      val schema = request.downField("params").downField("requestedSchema")
      assertEquals(schema.downField("properties").downField("title").get[String]("title"), Right("Meeting title"))
      assertEquals(schema.downField("properties").downField("title").get[Int]("maxLength"), Right(80))
      assertEquals(schema.downField("properties").downField("notifyAttendees").get[Boolean]("default"), Right(true))
      assertEquals(schema.get[List[String]]("required"), Right(List("title")))
      assert(state(result).startsWith("v1."), "the state is sealed")
    }

  test("three rounds: details, confirmation, booked"):
    for
      first  <- call()
      second <- call(Some(state(first)), Map("details" -> accept(Json.obj("title" -> Json.fromString("Standup")))))
      _       = assertEquals(second.hcursor.downField("inputRequests").downField("confirm").succeeded, true)
      third  <- call(Some(state(second)), Map("confirm" -> accept(Json.obj("confirm" -> Json.True))))
    yield assertEquals(text(third), "Booked 4B for Standup.")

  test("form content that breaks a refinement is not accepted"):
    for
      first  <- call()
      second <- call(Some(state(first)), Map("details" -> accept(Json.obj("title" -> Json.fromString("")))))
    yield assertEquals(text(second), "4B not booked.")

  test("a declined form ends the call"):
    for
      first  <- call()
      second <- call(Some(state(first)), Map("details" -> Json.obj("action" -> Json.fromString("decline"))))
    yield assertEquals(text(second), "4B not booked.")

  test("a forged requestState is refused before the handler runs"):
    // Skipping straight to the confirmation round with a title the user never gave.
    val payload = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString("confirm:Forged".getBytes)
    call(Some(s"v1.$payload.AAAA"), Map("confirm" -> accept(Json.obj("confirm" -> Json.True)))).map { result =>
      assertEquals(result.hcursor.get[Boolean]("isError"), Right(true))
      assert(text(result).contains("integrity check"))
    }

  test("a sealed requestState cannot be replayed with other arguments"):
    for
      first  <- call()
      second <- call(Some(state(first)), Map("details" -> accept(Json.obj("title" -> Json.fromString("Standup")))), room = "9Z")
    yield assertEquals(second.hcursor.get[Boolean]("isError"), Right(true))

  test("a client without elicitation gets a tool error, not an unanswerable request"):
    call(elicitation = false).map { result =>
      assertEquals(result.hcursor.get[Boolean]("isError"), Right(true))
      assert(text(result).contains("elicitation"))
    }
