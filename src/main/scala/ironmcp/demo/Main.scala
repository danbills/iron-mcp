package ironmcp
package demo

import cats.effect.{IO, IOApp}
import io.github.iltotore.iron.autoRefine
import com.comcast.ip4s.{ipv4, port}
import org.http4s.ember.server.EmberServerBuilder

/** Runs the demo server on http://127.0.0.1:8765/mcp
  *
  * Bound to loopback deliberately: an MCP server with no auth in front of it
  * has no business listening on 0.0.0.0.
  */
object Main extends IOApp.Simple:

  private val info = ServerInfo(name = "iron-mcp-demo", version = "0.1.0")

  def run: IO[Unit] =
    IronMcpServer
      .resource(info, DemoTools.all, instructions = Some("Demo server for iron-mcp."))
      .flatMap: server =>
        EmberServerBuilder
          .default[IO]
          .withHost(ipv4"127.0.0.1")
          .withPort(port"8765")
          .withHttpApp(server.routes.orNotFound)
          .build
      .useForever
