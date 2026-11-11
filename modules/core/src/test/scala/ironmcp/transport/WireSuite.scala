package ironmcp
package transport

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.McpServer
import munit.CatsEffectSuite

/** Wire's encoding rules, and Stdio.pipe's framing of good and bad lines. */
class WireSuite extends CatsEffectSuite:

  test("JSON that is not a JSON-RPC message is -32600, with the decoder's reason as data"):
    Wire.decode("""{"hello":"world"}""") match
      case Left(error) =>
        assertEquals(error.code, ErrorCode.InvalidRequest)
        assert(error.data.exists(_.isString), error.toString)
      case Right(other) => fail(s"decoded: $other")

  test("an error before the id is known is answered against a null id"):
    val line = Wire.errorResponse(JsonRpcError(ErrorCode.ParseError, "bad"))
    val json = parse(line).toOption.get.hcursor
    assertEquals(json.downField("id").focus, Some(Json.Null))
    assertEquals(json.downField("error").get[Int]("code"), Right(-32700))

  test("an empty decoder message is replaced, since JSON-RPC errors need one"):
    assertEquals(Wire.nonEmpty("") : String, "unspecified error")
    assertEquals(Wire.nonEmpty("why") : String, "why")

  test("every message kind survives encode and decode"):
    val messages = List[JsonRpcMessage](
      JsonRpcMessage.Request(RequestId.Text("a"), "tools/list", Some(Json.obj())),
      JsonRpcMessage.Notification("notifications/progress", Some(Json.obj("progress" -> Json.fromInt(1)))),
      JsonRpcMessage.Success(RequestId.Num(2), Json.obj("ok" -> Json.True)),
      JsonRpcMessage.Failure(Some(RequestId.Num(3)), JsonRpcError(ErrorCode.InternalError, "internal error"))
    )
    messages.foreach(m => assertEquals(Wire.decode(Wire.encode(m)), Right(m)))

  test("Stdio.pipe answers a malformed line with a parse error and skips blank ones"):
    val server = McpServer[IO](info = Implementation(name = "wire", version = "1"))
    Stream("", "   ", "{not json", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
      .through(Stdio.pipe(server))
      .compile
      .toList
      .map { lines =>
        assertEquals(lines.size, 1)
        assertEquals(parse(lines.head).flatMap(_.hcursor.downField("error").get[Int]("code")), Right(-32700))
      }
