package ironmcp
package server

import cats.{Applicative, ApplicativeThrow}
import cats.syntax.all.*
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
  *
  * A handler that fails comes back as an `isError` result naming the tool, never
  * as a protocol error: the model can read it and try again.
  */
final case class McpTool[F[_]](definition: Tool, invoke: Json => F[CallToolResult])

object McpTool:

  /** `McpTool[Args](name, description) { args => ... }`: the effect is inferred from the handler. */
  def apply[A](
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString] = None,
      annotations: Option[ToolAnnotations] = None,
      outputSchema: Option[JsonObject] = None
  ): Builder[A] =
    Builder(name, description, title, annotations, outputSchema)

  final class Builder[A] private[McpTool] (
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString],
      annotations: Option[ToolAnnotations],
      outputSchema: Option[JsonObject]
  ):
    def apply[F[_]](
        handler: A => F[CallToolResult]
    )(using F: ApplicativeThrow[F], decoder: Decoder[A], schema: JsonSchemaOf[A]): McpTool[F] =
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
            case Right(arguments) =>
              handler(arguments).handleError(error => CallToolResult.failed(s"$name failed: ${error.getMessage}"))
            // A refinement violation is the model's mistake to correct, so it
            // comes back as a tool error carrying Iron's own message.
            case Left(failure) => F.pure(CallToolResult.failed(failure.message))
      )

/** A [[ToolProvider]] over a fixed set of typed tools. */
final class ToolSet[F[_]](tools: List[McpTool[F]], ttlMs: CacheTtlMs = 60000L)(using F: Applicative[F])
    extends ToolProvider[F]:

  private val byName: Map[String, McpTool[F]] =
    tools.map(tool => (tool.definition.name: String) -> tool).toMap

  def list(params: ListToolsParams): F[ListToolsResult] =
    F.pure(ListToolsResult(tools.map(_.definition), ttlMs, CacheScope.`private`))

  def call(params: CallToolParams): F[CallToolResult | InputRequiredResult] =
    byName.get(params.name) match
      case Some(tool) =>
        tool.invoke(Json.fromJsonObject(params.arguments.getOrElse(JsonObject.empty))).widen[CallToolResult | InputRequiredResult]
      case None => F.pure(CallToolResult.failed(s"no such tool: ${params.name}"))

object ToolSet:
  def of[F[_]: Applicative](tools: McpTool[F]*): ToolSet[F] = ToolSet(tools.toList)
