package ironmcp
package server

import cats.effect.IO
import io.circe.{Decoder, Json, JsonObject}
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf

/** A tool defined by the type of its arguments.
  *
  * The input schema is derived from `A`, so it cannot disagree with what the
  * handler accepts; there is no second copy of the constraints to keep in step.
  * Decoding happens before the handler runs, so `A` is already valid — including
  * every Iron refinement on it.
  */
final case class McpTool(definition: Tool, invoke: Json => IO[CallToolResult])

object McpTool:

  def apply[A](
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString] = None,
      annotations: Option[ToolAnnotations] = None,
      outputSchema: Option[JsonObject] = None
  )(
      handler: A => IO[CallToolResult]
  )(using decoder: Decoder[A], schema: JsonSchemaOf[A]): McpTool =
    val input = schema.schema.as[ObjectSchema].getOrElse(ObjectSchema.empty)
    McpTool(
      definition = Tool(
        name = name,
        inputSchema = input,
        title = title,
        description = Some(description),
        outputSchema = outputSchema,
        annotations = annotations
      ),
      invoke = json =>
        decoder.decodeJson(json) match
          case Right(arguments) => handler(arguments)
          // A refinement violation is the model's mistake to correct, so it
          // comes back as a tool error carrying Iron's own message.
          case Left(failure) => IO.pure(CallToolResult.failed(failure.message))
    )

/** A [[ToolProvider]] over a fixed set of typed tools. */
final class ToolSet(tools: List[McpTool], ttlMs: CacheTtlMs = 60000L) extends ToolProvider:

  private val byName: Map[String, McpTool] =
    tools.map(tool => (tool.definition.name: String) -> tool).toMap

  def list(params: ListToolsParams): IO[ListToolsResult] =
    IO.pure(ListToolsResult(tools.map(_.definition), ttlMs, CacheScope.`private`))

  def call(params: CallToolParams): IO[CallToolResult | InputRequiredResult] =
    byName.get(params.name) match
      case Some(tool) => tool.invoke(Json.fromJsonObject(params.arguments.getOrElse(JsonObject.empty))).widen
      case None       => IO.pure(CallToolResult.failed(s"no such tool: ${params.name}"))

  extension (self: IO[CallToolResult])
    private def widen: IO[CallToolResult | InputRequiredResult] = self.map(identity)

object ToolSet:
  def of(tools: McpTool*): ToolSet = ToolSet(tools.toList)
