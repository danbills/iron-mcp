package ironmcp

import cats.Show
import io.circe.{Decoder, Encoder, Json, parser}
import io.circe.derivation.{Configuration, ConfiguredEncoder}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.schema.{ConfiguredJsonSchemaOf, JsonSchemaOf}
import ironmcp.server.*
import ironmcp.transport.Wire
import munit.FunSuite

class ToolOutputSuite extends FunSuite:

  type Result[A] = Either[Throwable, A]

  final case class Divide(dividend: Int, divisor: Int) derives Decoder, JsonSchemaOf

  type Remainder = Int :| (GreaterEqual[0] DescribedAs "What is left over")

  final case class Quotient(quotient: Int, remainder: Remainder, note: Option[String]) derives Encoder.AsObject, JsonSchemaOf

  final case class Shown(value: Int) derives Encoder.AsObject, JsonSchemaOf

  object Shown:
    given Show[Shown] = shown => s"the value is ${shown.value}"

  private def ok[A](value: A): Result[A] = Right(value)

  private val divide = McpTool[Divide]("divide", "Integer division.") { args =>
    ok:
      if args.divisor == 0 then Left("cannot divide by zero")
      else
        val q = args.dividend / args.divisor
        Right(Quotient(q, (args.dividend - q * args.divisor).assume, None))
  }

  private val shown = McpTool[Divide]("shown", "Shown.")(args => ok(Shown(args.dividend)))

  private val untyped = McpTool[Divide]("untyped", "Builds its own result.")(_ => ok(CallToolResult.text("done")))

  private val server = McpServer[Result](
    info = Implementation(name = "suite", version = "0.1.0"),
    tools = Some(ToolSet.of(divide, shown, untyped))
  )

  private val meta = RequestMeta(protocolVersion = LatestProtocolVersion, clientCapabilities = ClientCapabilities.none)

  /** Send a request over the wire and read back the reply's `result`. */
  private def roundTrip(method: Method, params: Json): Json =
    val sent = Wire.encode(JsonRpcMessage.Request(RequestId.Num(1L), method.wire.assume[Not[Empty]], Some(params)))
    val reply = for
      message <- Wire.decode(sent).left.map(error => AssertionError(s"could not encode a request: $error"))
      handled <- server.handle(message)
      json    <- parser.parse(handled.fold("null")(Wire.encode))
    yield json.hcursor.downField("result").focus
    reply.toOption.flatten.getOrElse(fail(s"no result for $method"))

  private def listed(name: String): Json =
    roundTrip(Method.ToolsList, Json.obj("_meta" -> meta.asJson)).hcursor
      .downField("tools").as[List[Json]].toOption.toList.flatten
      .find(_.hcursor.get[String]("name") == Right(name))
      .getOrElse(fail(s"$name is not listed"))

  private def call(name: String, args: (String, Json)*): Json =
    roundTrip(Method.ToolsCall, Json.obj("name" -> name.asJson, "_meta" -> meta.asJson, "arguments" -> Json.obj(args*)))

  test("the output schema is derived from the type the handler returns"):
    val schema = listed("divide").hcursor.downField("outputSchema")
    assertEquals(schema.get[String]("type"), Right("object"))
    assertEquals(schema.get[List[String]]("required"), Right(List("quotient", "remainder")))
    assertEquals(schema.downField("properties").downField("remainder").focus, Some(Json.obj(
      "type" -> "integer".asJson, "minimum" -> 0.asJson, "description" -> "What is left over".asJson
    )))

  test("structured content is the encoded result, with absent options left out rather than null"):
    val result = call("divide", "dividend" -> 7.asJson, "divisor" -> 2.asJson)
    assertEquals(result.hcursor.downField("structuredContent").focus, Some(Json.obj("quotient" -> 3.asJson, "remainder" -> 1.asJson)))
    assertEquals(result.hcursor.get[Boolean]("isError"), Right(false))

  test("without a Show, the text block is the serialized JSON"):
    val result = call("divide", "dividend" -> 7.asJson, "divisor" -> 2.asJson)
    assertEquals(result.hcursor.downField("content").downArray.get[String]("text"), Right("""{"quotient":3,"remainder":1}"""))

  test("a Show renders the text block; the structured content is unchanged"):
    val result = call("shown", "dividend" -> 7.asJson, "divisor" -> 2.asJson)
    assertEquals(result.hcursor.downField("content").downArray.get[String]("text"), Right("the value is 7"))
    assertEquals(result.hcursor.downField("structuredContent").focus, Some(Json.obj("value" -> 7.asJson)))

  test("a Left is an isError result the model can read, with no structured content"):
    val result = call("divide", "dividend" -> 7.asJson, "divisor" -> 0.asJson)
    assertEquals(result.hcursor.get[Boolean]("isError"), Right(true))
    assertEquals(result.hcursor.downField("content").downArray.get[String]("text"), Right("cannot divide by zero"))
    assertEquals(result.hcursor.downField("structuredContent").focus, None)

  test("a tool that builds its own CallToolResult declares no output schema"):
    assertEquals(listed("untyped").hcursor.downField("outputSchema").focus, None)

  given Configuration = Configuration.default.withSnakeCaseMemberNames

  final case class Snake(pressureHpa: Double, windKmh: Option[Double]) derives ConfiguredEncoder, ConfiguredJsonSchemaOf

  test("a configured schema names the keys the configured encoder writes"):
    val schema  = summon[JsonSchemaOf[Snake]].schema
    val encoded = Snake(1015.0, Some(4.0)).asJson
    assertEquals(schema.hcursor.downField("properties").keys.map(_.toList), encoded.hcursor.keys.map(_.toList))
    assertEquals(schema.hcursor.get[List[String]]("required"), Right(List("pressure_hpa")))
