package ironmcp
package nasa

import cats.effect.{IO, IOApp}
import io.github.iltotore.iron.autoRefine
import org.http4s.ember.client.EmberClientBuilder
import ironmcp.protocol.*
import ironmcp.server.*
import ironmcp.transport.Stdio

/** A stdio MCP server exposing NASA's Astronomy Picture of the Day (APOD) API as
  * tools.
  *
  * The tools' argument types are the schema: `ApodDate` carries its format and
  * description in the type, so the advertised JSON Schema and the runtime
  * validation are one declaration that cannot drift apart. The tool is a program
  * over the [[ApodOp]] algebra ([[ApodTools]]), interpreted against api.nasa.gov
  * by [[ApodHttp4s]] with one HTTP client for the life of the server.
  *
  * APOD requires an API key: set `NASA_API_KEY` in the environment before
  * starting the server. Without it, the tool returns a failed result telling
  * the model to obtain a free key at https://api.nasa.gov.
  */
object Main extends IOApp.Simple:

  def run: IO[Unit] =
    EmberClientBuilder.default[IO].build.use { client =>
      Stdio.serve(
        McpServer(
          info = Implementation(name = "iron-mcp-nasa", version = "0.1.0"),
          instructions = Some("NASA's Astronomy Picture of the Day (APOD) via api.nasa.gov."),
          tools = Some(ApodTools(ApodHttp4s(client, sys.env.get("NASA_API_KEY").filter(_.nonEmpty))))
        )
      )
    }
