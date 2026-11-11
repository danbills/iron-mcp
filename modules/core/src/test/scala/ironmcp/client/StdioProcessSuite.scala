package ironmcp
package client

import cats.effect.IO
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** StdioClient with real child processes: small `sh` scripts that play a server, so nothing but the shell is needed
  * (no network, no build). StdioClientSuite covers the framing in memory.
  */
class StdioProcessSuite extends CatsEffectSuite:

  private val request = new JsonRpcMessage.Request(RequestId.Num(1), "x/where", None)

  test("a child answers over stdout, with its env and working directory, and its notifications reach the client"):
    // reads one request, sends a notification and a reply naming $GREETING and its directory, logs to stderr,
    // then exits when its stdin closes
    val script =
      """read line
        |echo '{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":1,"progress":1}}'
        |printf '{"jsonrpc":"2.0","id":1,"result":{"greeting":"%s","dir":"%s"}}\n' "$GREETING" "$(pwd)"
        |echo "stderr is passed through" >&2
        |cat > /dev/null""".stripMargin
    for
      notes <- IO.ref(List.empty[String])
      reply <- StdioClient
                 .spawn[IO]("sh", List("-c", script), env = Map("GREETING" -> "bonjour"), workingDirectory = Some("/tmp"),
                   onNotification = Some(n => notes.update(_ :+ (n.method: String))))
                 .use(_.request(request))
      got   <- notes.get
    yield
      reply match
        case JsonRpcMessage.Success(_, result) =>
          assertEquals(result.hcursor.get[String]("greeting"), Right("bonjour"))
          assertEquals(result.hcursor.get[String]("dir").map(_.stripSuffix("/")), Right("/tmp"))
        case other => fail(s"$other")
      assertEquals(got, List("notifications/progress"))

  test("with every default, a child that exits at once closes the connection"):
    StdioClient.spawn[IO]("true").use(_.request(request).attempt).map {
      case Left(McpClientError.Closed(_)) => ()
      case other                          => fail(s"not closed: $other")
    }

  test("a child that ignores the end of its stdin is killed after exitGrace"):
    StdioClient
      .spawn[IO]("sh", List("-c", "exec sleep 30"), exitGrace = 200.millis)
      .use(_ => IO.unit)
      .timed
      .map((took, _) => assert(took < 10.seconds, s"took $took"))

  test("a reply carrying a result passes through untouched; with no handler, notifications are dropped"):
    val script =
      """read line
        |echo '{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":1,"progress":1}}'
        |echo '{"jsonrpc":"2.0","id":1,"result":{"n":[1,2]}}'
        |cat > /dev/null""".stripMargin
    StdioClient.spawn[IO]("sh", List("-c", script)).use(_.request(request)).map {
      case JsonRpcMessage.Success(_, result) => assertEquals(result, Json.obj("n" -> Json.arr(Json.fromInt(1), Json.fromInt(2))))
      case other                             => fail(s"$other")
    }
