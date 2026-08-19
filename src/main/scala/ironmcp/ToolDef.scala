package ironmcp

import cats.effect.IO
import cats.effect.std.Dispatcher
import io.circe.{Decoder, Json}
import io.circe.parser
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.Mono

/** What a tool hands back.
  *
  * MCP draws a hard line: a tool that *fails at its job* returns a normal
  * result carrying `isError: true`, so the model can see and react to it.
  * A JSON-RPC error means the protocol itself broke. `Failure` is the former.
  */
enum ToolOutcome:
  case Text(value: String)
  case Structured(text: String, json: Json)
  case Failure(message: String)

/** A tool whose arguments are decoded into `A` before your code runs.
  *
  * `A` is expected to be a case class of Iron-refined fields. Every constraint
  * on it — port ranges, non-empty strings, regex shapes — is enforced by the
  * Circe decoder at the protocol boundary, and a violation becomes a tool
  * error the model can read, not an exception.
  */
final case class ToolDef[A](
    name: ToolName,
    description: NonEmptyString,
    inputSchema: Json,
    handler: A => IO[ToolOutcome],
    title: Option[NonEmptyString] = None,
    outputSchema: Option[Json] = None
)(using val decoder: Decoder[A]):

  private[ironmcp] def toSpecification(
      mapper: McpJsonMapper,
      dispatcher: Dispatcher[IO]
  ): AsyncToolSpecification =
    // The no-arg builder() is deprecated in 2.0; name + schema are now required
    // up front, which is exactly the invariant we already hold.
    val builder = McpSchema.Tool
      .builder(name, mapper, inputSchema.noSpaces)
      .description(description)
    title.foreach(t => builder.title(t))
    outputSchema.foreach(s => builder.outputSchema(mapper, s.noSpaces))

    val call: (McpTransportContext, McpSchema.CallToolRequest) => Mono[McpSchema.CallToolResult] =
      (_, request) =>
        val outcome = ToolDef.arguments(mapper, request).flatMap(decoder.decodeJson(_).left.map(_.getMessage)) match
          case Left(why)   => IO.pure(ToolOutcome.Failure(why))
          case Right(args) => handler(args).handleError(e => ToolOutcome.Failure(errorMessage(e)))
        ReactorInterop.ioToMono(dispatcher)(outcome.map(ToolDef.toResult(mapper, _)))

    AsyncToolSpecification.builder().tool(builder.build()).callHandler(call.apply).build()

  /** Never leak a stack trace to a model; it is tokens with no signal. */
  private def errorMessage(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

object ToolDef:

  /** The SDK hands arguments over as a `java.util.Map[String, Object]`. Rather
    * than hand-roll a converter, round-trip it through the SDK's own mapper —
    * whatever it can serialize, Circe can then parse.
    */
  private[ironmcp] def arguments(
      mapper: McpJsonMapper,
      request: McpSchema.CallToolRequest
  ): Either[String, Json] =
    val args = Option(request.arguments()).getOrElse(java.util.Map.of[String, Object]())
    try parser.parse(mapper.writeValueAsString(args)).left.map(_.getMessage)
    catch case scala.util.control.NonFatal(e) => Left(s"unreadable arguments: ${e.getMessage}")

  private[ironmcp] def toResult(mapper: McpJsonMapper, outcome: ToolOutcome): McpSchema.CallToolResult =
    outcome match
      case ToolOutcome.Text(value) =>
        McpSchema.CallToolResult.builder().addTextContent(value).isError(false).build()
      case ToolOutcome.Structured(text, json) =>
        McpSchema.CallToolResult
          .builder()
          .addTextContent(text)
          .structuredContent(mapper, json.noSpaces)
          .isError(false)
          .build()
      case ToolOutcome.Failure(message) =>
        McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build()
