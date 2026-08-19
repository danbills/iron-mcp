package ironmcp

import cats.effect.IO
import io.circe.{Json, parser}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.protocol.MetaObject.given
import ironmcp.server.*
import ironmcp.transport.Wire
import munit.CatsEffectSuite

class ProtocolSuite extends CatsEffectSuite:

  private val echo = new ToolProvider:
    def list(params: ListToolsParams): IO[ListToolsResult] =
      IO.pure(
        ListToolsResult(
          tools = List(Tool(name = "echo", inputSchema = ObjectSchema(), description = Some("Echoes."))),
          ttlMs = 60000L,
          cacheScope = CacheScope.`private`
        )
      )

    def call(params: CallToolParams): IO[CallToolResult | InputRequiredResult] =
      IO.pure(CallToolResult.text(params.arguments.fold("")(_.toJson.noSpaces)))

  private val server = McpServer(
    info = Implementation(name = "suite", version = "0.1.0"),
    instructions = Some("A test server."),
    tools = Some(echo)
  )

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  private def request(id: Int, method: Method, params: Json): String =
    Wire.encode(JsonRpcMessage.Request(RequestId.Num(id.toLong), method.wire.assume[Not[Empty]], Some(params)))

  private def roundTrip(id: Int, method: Method, params: Json): IO[Json] =
    Wire.decode(request(id, method, params)) match
      case Left(error) => IO.raiseError(AssertionError(s"could not encode a request: $error"))
      case Right(message) =>
        server.handle(message).flatMap {
          case Some(reply) => IO.fromEither(parser.parse(Wire.encode(reply)))
          case None        => IO.pure(Json.Null)
        }

  test("capabilities are derived from providers, never declared"):
    // No resource provider was supplied, so no resource capability may appear.
    assertEquals(server.capabilities.tools.isDefined, true)
    assertEquals(server.capabilities.resources, None)
    assertEquals(server.capabilities.prompts, None)

  test("server/discover advertises the revision and is publicly cacheable"):
    roundTrip(1, Method.Discover, Json.obj("_meta" -> meta.asJson)).map { json =>
      val result = json.hcursor.downField("result")
      assertEquals(result.get[List[String]]("supportedVersions"), Right(List("2026-07-28")))
      assertEquals(result.get[String]("cacheScope"), Right("public"))
      assertEquals(result.get[String]("resultType"), Right("complete"))
      assertEquals(
        result.downField("_meta").downField("io.modelcontextprotocol/serverInfo").get[String]("name"),
        Right("suite")
      )
    }

  test("a request without the _meta envelope is rejected, not guessed at"):
    roundTrip(2, Method.ToolsList, Json.obj()).map { json =>
      assertEquals(json.hcursor.downField("error").get[Int]("code"), Right(-32602))
    }

  test("a foreign protocol version yields -32022 carrying the supported list"):
    val stale = meta.copy(protocolVersion = "2025-11-25")
    roundTrip(3, Method.ToolsList, Json.obj("_meta" -> stale.asJson)).map { json =>
      val error = json.hcursor.downField("error")
      assertEquals(error.get[Int]("code"), Right(-32022))
      assertEquals(error.downField("data").get[List[String]]("supported"), Right(List("2026-07-28")))
      assertEquals(error.downField("data").get[String]("requested"), Right("2025-11-25"))
    }

  test("tools/list returns the tool with an object input schema"):
    roundTrip(4, Method.ToolsList, Json.obj("_meta" -> meta.asJson)).map { json =>
      val tool = json.hcursor.downField("result").downField("tools").downArray
      assertEquals(tool.get[String]("name"), Right("echo"))
      assertEquals(tool.downField("inputSchema").get[String]("type"), Right("object"))
    }

  test("an unserved capability is method-not-found, not a crash"):
    roundTrip(5, Method.PromptsList, Json.obj("_meta" -> meta.asJson)).map { json =>
      assertEquals(json.hcursor.downField("error").get[Int]("code"), Right(-32601))
    }

  test("an unknown method is -32601"):
    roundTrip(6, Method.Unknown("tools/teleport"), Json.obj("_meta" -> meta.asJson)).map { json =>
      assertEquals(json.hcursor.downField("error").get[Int]("code"), Right(-32601))
    }

  test("a notification draws no reply"):
    roundTrip(7, Method.Cancelled, Json.obj("_meta" -> meta.asJson)).map { json =>
      // sent as a request above, so it answers; the notification path is below
      assert(json.hcursor.downField("error").succeeded || json.hcursor.downField("result").succeeded)
    }

  test("notifications are answered with silence, per JSON-RPC"):
    val notification = JsonRpcMessage.Notification("notifications/cancelled".assume[Not[Empty]], None)
    server.handle(notification).map(reply => assertEquals(reply, None))

  test("malformed JSON is a parse error, not an exception"):
    Wire.decode("{ not json") match
      case Left(error) => IO(assertEquals(error.code, ErrorCode.ParseError))
      case Right(_)    => IO(fail("garbage should not decode"))

  test("optional fields are omitted, never encoded as null"):
    val encoded = Wire.encode(JsonRpcMessage.Notification("notifications/tools/list_changed".assume[Not[Empty]], None))
    assert(!encoded.contains("null"), s"nulls leaked onto the wire: $encoded")

  test("a _meta key that breaks the spec grammar fails to decode"):
    val bad = Json.obj("_meta" -> Json.obj("bad key!" -> Json.fromString("x")))
    assert(bad.hcursor.get[MetaObject]("_meta").isLeft, "an invalid _meta key must not decode")

  test("an ill-formed tool name cannot be decoded into a Tool"):
    val bad = Json.obj(
      "name"        -> Json.fromString("not a valid name!"),
      "inputSchema" -> Json.obj("type" -> Json.fromString("object"))
    )
    assert(bad.as[Tool].isLeft, "tool names must match the host-enforced pattern")

  test("an input schema that is not an object is rejected"):
    val bad = Json.obj("type" -> Json.fromString("array"))
    assert(bad.as[ObjectSchema].isLeft, "only object schemas are legal tool inputs")

  test("content blocks round-trip through their type tag"):
    val blocks: List[ContentBlock] = List(
      ContentBlock.Text("hello"),
      ContentBlock.Image("aGk=", "image/png"),
      ContentBlock.Embedded(ResourceContents.Text("file:///x", "body"))
    )
    blocks.foreach { block =>
      val json = block.asJson.deepDropNullValues
      assertEquals(json.as[ContentBlock], Right(block), s"failed on $json")
    }

  test("a resource block carrying both text and blob is rejected"):
    val bad = Json.obj(
      "uri"  -> Json.fromString("file:///x"),
      "text" -> Json.fromString("a"),
      "blob" -> Json.fromString("YQ==")
    )
    assert(bad.as[ResourceContents].isLeft, "text and blob are mutually exclusive")

  test("request ids keep their JSON type across a round trip"):
    assertEquals((RequestId.Num(7L): RequestId).asJson.noSpaces, "7")
    assertEquals((RequestId.Text("seven": NonEmptyString): RequestId).asJson.noSpaces, "\"seven\"")
    assertEquals(parser.parse("7").flatMap(_.as[RequestId]), Right(RequestId.Num(7L)))

  extension (self: io.circe.JsonObject) private def toJson: Json = Json.fromJsonObject(self)
