package ironmcp
package demo

import cats.Show
import cats.effect.{ExitCode, IO, IOApp}
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import ironmcp.client.{McpClient, StdioClient}
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
object Main extends IOApp:

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

  /** `Main` serves over stdio. `Main client <command> [args...]` is a client instead: it spawns
    * that server (this binary, say), lists its tools and calls `greet`, so the client path,
    * process spawning included, is linked and run on Scala Native too.
    */
  def run(args: List[String]): IO[ExitCode] = args match
    case "client" :: command :: rest =>
      StdioClient.spawn[IO](command, rest).use { transport =>
        for
          client <- McpClient[IO](transport)
          found  <- client.discover
          tools  <- client.allTools
          result <- client.callTool("greet", JsonObject("name" -> "Native".asJson, "times" -> 2.asJson))
          _ <- IO.println(s"server speaks ${found.supportedVersions.mkString(", ")}; tools: ${tools.map(t => t.name: String).mkString(", ")}")
          _ <- IO.println(result match
                 case r: CallToolResult      => r.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString
                 case _: InputRequiredResult => "input required")
        yield ExitCode.Success
      }
    case _ => Stdio.serve(server).as(ExitCode.Success)
