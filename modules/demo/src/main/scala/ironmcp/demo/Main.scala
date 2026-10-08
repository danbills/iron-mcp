package ironmcp
package demo

import cats.Show
import cats.effect.{IO, IOApp}
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.transport.Stdio

/** A stdio MCP server.
  *
  * Note what is absent: no hand-written JSON Schema. `Greet`'s types are the
  * schema — `Not[Empty]` becomes `minLength: 1`, `Interval.Closed[1, 10]`
  * becomes `minimum`/`maximum`, and `DescribedAs` becomes `description` — all
  * derived at compile time from the type the handler already accepts. Widen the
  * type and the advertised schema widens in the same edit. The output schema
  * comes the same way, from `Greeting`, the type the handler returns.
  */
object Main extends IOApp.Simple:

  /** Descriptions live in the type, alongside the constraints they describe,
    * so both reach the model from one declaration and neither can drift.
    */
  type Recipient = String :| (Not[Empty] DescribedAs "Who to greet")
  type Repeats   = Int :| (Interval.Closed[1, 10] DescribedAs "How many times to repeat the greeting")

  final case class Greet(name: Recipient, times: Repeats) derives Decoder, JsonSchemaOf

  final case class Greeting(greeting: String) derives Encoder.AsObject, JsonSchemaOf

  object Greeting:
    given Show[Greeting] = _.greeting

  private val greet = McpTool[Greet](
    name = "greet",
    description = "Greet someone, one to ten times.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(false)))
  ) { args =>
    IO.pure(Greeting(List.fill(args.times: Int)(s"Hello, ${args.name: String}!").mkString(" ")))
  }

  private val server = McpServer(
    info = Implementation(name = "iron-mcp-demo", version = "0.1.0"),
    instructions = Some("Demo server for iron-mcp."),
    tools = Some(ToolSet.of(greet))
  )

  def run: IO[Unit] = Stdio.serve(server)
