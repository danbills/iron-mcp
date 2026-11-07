package ironmcp

import cats.effect.IO
import io.circe.{Json, parser}
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.*
import ironmcp.transport.Wire
import munit.CatsEffectSuite

/** A provider that fails must not take the transport down with it. */
class RecoverySuite extends CatsEffectSuite:

  private val resources = new ResourceProvider[IO]:
    def list(params: ListResourcesParams): IO[ListResourcesResult] = IO.raiseError(RuntimeException("db password is hunter2"))
    def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult] =
      IO.raiseError(McpException.resourceNotFound(params.uri))

  private val server = McpServer[IO](info = Implementation(name = "recovery", version = "0.1.0"), resources = Some(resources))

  private val meta = """{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

  private def send(line: String): IO[Json] =
    IO.fromEither(Wire.decode(line).left.map(e => AssertionError(e.toString)))
      .flatMap(server.handleRecovering)
      .flatMap(reply => IO.fromEither(parser.parse(Wire.encode(reply.get))))

  test("McpException becomes its own error: -32002 for a missing resource"):
    send(s"""{"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"file:///nope","_meta":$meta}}""").map { json =>
      assertEquals(json.hcursor.get[Int]("id"), Right(7))
      assertEquals(json.hcursor.downField("error").get[Int]("code"), Right(-32002))
      assertEquals(json.hcursor.downField("error").downField("data").get[String]("uri"), Right("file:///nope"))
    }

  test("any other failure is -32603 and does not leak its message"):
    send(s"""{"jsonrpc":"2.0","id":8,"method":"resources/list","params":{"_meta":$meta}}""").map { json =>
      assertEquals(json.hcursor.downField("error").get[Int]("code"), Right(-32603))
      assert(!json.noSpaces.contains("hunter2"))
    }

  test("onUnhandled sees an unexpected exception, not an McpException; a failing hook still replies"):
    for
      seen <- IO.ref(List.empty[(String, String)])
      hook: OnUnhandled[IO] = (message, error) =>
        val method = message match
          case JsonRpcMessage.Request(_, method, _) => method: String
          case _                                    => "?"
        seen.update((method, error.getMessage) :: _) *> IO.raiseError(RuntimeException("the hook broke"))
      hooked = McpServer[IO](info = Implementation(name = "recovery", version = "0.1.0"), resources = Some(resources), onUnhandled = Some(hook))
      list <- IO.fromEither(Wire.decode(s"""{"jsonrpc":"2.0","id":1,"method":"resources/list","params":{"_meta":$meta}}""").left.map(e => AssertionError(e.toString)))
      read <- IO.fromEither(Wire.decode(s"""{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"file:///nope","_meta":$meta}}""").left.map(e => AssertionError(e.toString)))
      listReply <- hooked.handleRecovering(list)
      readReply <- hooked.handleRecovering(read)
      calls <- seen.get
    yield
      assertEquals(calls, List("resources/list" -> "db password is hunter2"))
      assert(listReply.exists { case JsonRpcMessage.Failure(_, e) => e.code == ErrorCode.InternalError; case _ => false })
      assert(readReply.exists { case JsonRpcMessage.Failure(_, e) => e.code == ErrorCode.ResourceNotFound; case _ => false })
