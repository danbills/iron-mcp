package ironmcp.apps.view

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.{Decoder, Json, JsonObject}
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf

/** A tool the View offers its host, as ext-apps' `App.registerTool`: the host
  * lists it (`tools/list`) and calls it (`tools/call`), usually on the model's
  * behalf, to read or drive what the View shows.
  *
  * Built like a server tool (`McpTool`): the arguments decode into `A`, whose
  * type is the input schema, and the handler's output type decides the result
  * and the `outputSchema` ([[ToolOutput]]). Arguments that do not decode, and a
  * handler that fails, are `isError` results the caller can read.
  */
final case class ViewTool[F[_]](definition: Tool, call: JsonObject => F[CallToolResult]):
  def name: String = definition.name

object ViewTool:

  /** `ViewTool[Args](name, description) { args => ... }`. */
  def apply[A](
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString] = None,
      annotations: Option[ToolAnnotations] = None
  ): Builder[A] =
    Builder(name, description, title, annotations)

  final class Builder[A] private[ViewTool] (
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString],
      annotations: Option[ToolAnnotations]
  ):
    def apply[F[_], O](
        handler: A => F[O]
    )(using F: MonadThrow[F], decoder: Decoder[A], schema: JsonSchemaOf[A], output: ToolOutput[O]): ViewTool[F] =
      ViewTool(
        Tool(
          name = name,
          inputSchema = schema.schema.as[ObjectSchema].getOrElse(ObjectSchema.empty),
          title = title,
          description = Some(description),
          outputSchema = output.schema,
          annotations = annotations
        ),
        arguments =>
          decoder.decodeJson(Json.fromJsonObject(arguments)) match
            case Left(failure) => F.pure(CallToolResult.failed(failure.message))
            case Right(args) =>
              handler(args).map(output.result).handleError(error => CallToolResult.failed(s"$name failed: ${error.getMessage}"))
      )
