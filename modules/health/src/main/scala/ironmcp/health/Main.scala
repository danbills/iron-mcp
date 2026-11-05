package ironmcp
package health

import cats.effect.{IO, IOApp}
import cats.effect.std.Random
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.UiResources
import ironmcp.protocol.*
import ironmcp.server.*
import ironmcp.transport.Stdio

import scala.io.Source

/** A stdio MCP server with an MCP App: a system health dashboard rendered by
  * Apps-capable hosts (Handbook §7.4, `chapter07/health-dashboard`).
  */
object Main extends IOApp.Simple:

  private val loadDashboard: IO[String] =
    IO.blocking(Source.fromResource("ironmcp/health/dashboard.html")(using scala.io.Codec.UTF8).mkString)

  def run: IO[Unit] =
    for
      random <- Random.scalaUtilRandom[IO]
      html   <- loadDashboard
      _ <- Stdio.serve(
             McpServer(
               info = Implementation(name = "System Health Monitor", version = "1.0.0"),
               tools = Some(HealthTools(SimulatedHealth(random))),
               resources = Some(UiResources.of[IO](HealthTools.dashboard(html)))
             )
           )
    yield ()
