package ironmcp
package weather

import cats.effect.{IO, IOApp}
import io.github.iltotore.iron.autoRefine
import org.http4s.ember.client.EmberClientBuilder
import ironmcp.protocol.*
import ironmcp.server.*
import ironmcp.transport.Stdio

/** A stdio MCP server exposing the U.S. National Weather Service (weather.gov)
  * API as tools.
  *
  * The tools' argument types are the schema: `Latitude` / `Longitude` carry
  * their bounds and descriptions in the type, so the advertised JSON Schema and
  * the runtime validation are one declaration that cannot drift apart. Each tool
  * is a program over the [[NwsOp]] algebra ([[WeatherTools]]); here those
  * programs are interpreted against api.weather.gov by [[NwsHttp4s]], sharing one
  * HTTP client for the life of the server.
  */
object Main extends IOApp.Simple:

  def run: IO[Unit] =
    EmberClientBuilder.default[IO].build.use { client =>
      Stdio.serve(
        McpServer(
          info = Implementation(name = "iron-mcp-weather", version = "0.1.0"),
          instructions = Some("U.S. weather via the National Weather Service (weather.gov)."),
          tools = Some(WeatherTools(NwsHttp4s(client)))
        )
      )
    }
