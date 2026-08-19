package ironmcp

import cats.effect.IO
import io.circe.parser
import io.github.iltotore.iron.autoRefine
import ironmcp.demo.DemoTools
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*

/** Exercises the server the way a host does: real JSON-RPC over the real
  * routes. No mocks — if the wire format is wrong, these fail.
  */
class ProtocolSuite extends CatsEffectSuite:

  private val info = ServerInfo(name = "iron-mcp-test", version = "0.1.0")

  private val server = ResourceSuiteLocalFixture(
    "server",
    IronMcpServer.resource(info, DemoTools.all)
  )
  override def munitFixtures = List(server)

  private def post(body: String): IO[(Status, io.circe.Json)] =
    val request = Request[IO](Method.POST, uri"/mcp").withEntity(body)
    server().routes.orNotFound.run(request).flatMap: response =>
      response.bodyText.compile.string.map: text =>
        (response.status, parser.parse(text).getOrElse(io.circe.Json.Null))

  private def rpc(id: Int, method: String, params: String = "{}"): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

  private def call(name: String, args: String): IO[io.circe.Json] =
    post(rpc(2, "tools/call", s"""{"name":"$name","arguments":$args}""")).map(_._2)

  test("initialize returns a protocol version and our server info"):
    post(rpc(1, "initialize",
      """{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"suite","version":"1.0.0"}}""")
    ).map: (status, json) =>
      assertEquals(status, Status.Ok)
      val result = json.hcursor.downField("result")
      assert(result.get[String]("protocolVersion").isRight, s"no protocolVersion in $json")
      assertEquals(result.downField("serverInfo").get[String]("name"), Right("iron-mcp-test"))

  test("a notification is accepted with no response body"):
    post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").map: (status, _) =>
      assertEquals(status, Status.Accepted)

  test("tools/list advertises both demo tools with their schemas"):
    post(rpc(1, "tools/list")).map: (_, json) =>
      val names = json.hcursor.downField("result").downField("tools").values.toList.flatten
        .flatMap(_.hcursor.get[String]("name").toOption)
      assertEquals(names.sorted, List("describe_port", "greet"))

  test("a valid call runs the handler and returns structured content"):
    call("greet", """{"name":"Dan","times":2}""").map: json =>
      val result = json.hcursor.downField("result")
      assertEquals(result.get[Boolean]("isError"), Right(false))
      assertEquals(
        result.downField("structuredContent").get[String]("greeting"),
        Right("Hello, Dan! Hello, Dan!")
      )

  test("an Iron violation the JSON Schema cannot express becomes a tool error"):
    // The schema says `name` is a string and says nothing about emptiness.
    // Only `NonEmptyString` rejects this — and it must arrive as isError,
    // not as a JSON-RPC error, so the model can read and correct it.
    call("greet", """{"name":"","times":1}""").map: json =>
      val result = json.hcursor.downField("result")
      assertEquals(result.get[Boolean]("isError"), Right(true))
      assert(json.noSpaces.contains("name"), s"error should name the bad field: $json")

  test("an out-of-range refinement is rejected before the handler runs"):
    call("greet", """{"name":"Dan","times":50}""").map: json =>
      assertEquals(json.hcursor.downField("result").get[Boolean]("isError"), Right(true))

  test("describe_port classifies a valid port"):
    call("describe_port", """{"port":8080}""").map: json =>
      assertEquals(
        json.hcursor.downField("result").downField("structuredContent").get[String]("kind"),
        Right("registered")
      )

  test("a stateless server offers no SSE stream to GET"):
    server().routes.orNotFound.run(Request[IO](Method.GET, uri"/mcp")).map: response =>
      assertEquals(response.status, Status.MethodNotAllowed)

  test("a malformed message is a JSON-RPC error, not a crash"):
    post("{ not json").map: (status, json) =>
      assertEquals(status, Status.BadRequest)
      assert(json.hcursor.downField("error").succeeded, s"expected an error object: $json")
