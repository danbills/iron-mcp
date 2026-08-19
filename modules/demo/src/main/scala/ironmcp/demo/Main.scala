package ironmcp
package demo

import cats.effect.{IO, IOApp}
import io.circe.{Decoder, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.transport.Stdio

/** A stdio MCP server.
  *
  * Note what is absent: no hand-written JSON Schema. `Greet`'s constraints are
  * the schema — `Not[Empty]` becomes `minLength: 1`, `Interval.Closed[1, 10]`
  * becomes `minimum`/`maximum` — derived at compile time from the type the
  * handler already accepts. Widen the type and the advertised schema widens in
  * the same edit.
  */
object Main extends IOApp.Simple:

  final case class Greet(
      name: NonEmptyString,
      times: Int :| Interval.Closed[1, 10]
  ) derives Decoder,
        JsonSchemaOf

  private val greet = McpTool[Greet](
    name = "greet",
    description = "Greet someone, one to ten times.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(false)))
  ) { args =>
    val line = List.fill(args.times)(s"Hello, ${args.name: String}!").mkString(" ")
    IO.pure(CallToolResult.structured(line, Json.obj("greeting" -> Json.fromString(line))))
  }

  private val server = McpServer(
    info = Implementation(name = "iron-mcp-demo", version = "0.1.0"),
    instructions = Some("Demo server for iron-mcp."),
    tools = Some(ToolSet.of(greet))
  )

  def run: IO[Unit] = Stdio.serve(server)
