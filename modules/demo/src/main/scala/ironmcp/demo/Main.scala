package ironmcp
package demo

import cats.effect.{IO, IOApp}
import io.circe.Json
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.server.*
import ironmcp.transport.Stdio

/** A stdio MCP server, which is what every local harness spawns.
  *
  * Built as a Scala Native binary this starts in single-digit milliseconds —
  * the reason the whole stack avoids reflection.
  */
object Main extends IOApp.Simple:

  /** Arguments are decoded into a refined case class, so the handler cannot be
    * reached with a value that violates its constraints.
    */
  final case class Greet(name: NonEmptyString, times: Int :| Interval.Closed[1, 10]) derives io.circe.Decoder

  private val greetSchema = ObjectSchema(
    properties = Map(
      "name"  -> Json.obj("type" -> Json.fromString("string"), "description" -> Json.fromString("Who to greet")),
      "times" -> Json.obj(
        "type"    -> Json.fromString("integer"),
        "minimum" -> Json.fromInt(1),
        "maximum" -> Json.fromInt(10)
      )
    ),
    required = List("name", "times")
  )

  private val tools = new ToolProvider:
    def list(params: ListToolsParams): IO[ListToolsResult] =
      IO.pure(
        ListToolsResult(
          tools = List(
            Tool(
              name = "greet",
              inputSchema = greetSchema,
              description = Some("Greet someone, one to ten times."),
              annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(false)))
            )
          ),
          ttlMs = 60000L,
          cacheScope = CacheScope.`private`
        )
      )

    def call(params: CallToolParams): IO[CallToolResult | InputRequiredResult] =
      params.name match
        case "greet" =>
          val decoded = Json.fromJsonObject(params.arguments.getOrElse(io.circe.JsonObject.empty)).as[Greet]
          IO.pure(decoded match
            case Left(failure) => CallToolResult.failed(failure.message)
            case Right(greet) =>
              val line = List.fill(greet.times)(s"Hello, ${greet.name: String}!").mkString(" ")
              CallToolResult.structured(line, Json.obj("greeting" -> Json.fromString(line)))
          )
        case other =>
          IO.pure(CallToolResult.failed(s"no such tool: $other"))

  private val server = McpServer(
    info = Implementation(name = "iron-mcp-demo", version = "0.1.0"),
    instructions = Some("Demo server for iron-mcp."),
    tools = Some(tools)
  )

  def run: IO[Unit] = Stdio.serve(server)
