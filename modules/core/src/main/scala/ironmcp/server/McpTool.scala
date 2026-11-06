package ironmcp
package server

import cats.ApplicativeThrow
import cats.syntax.all.*
import io.circe.{Decoder, Json, JsonObject}
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf

/** A tool defined by the types of its arguments and its result.
  *
  * The input schema is derived from `A`, so it cannot disagree with what the
  * handler accepts; there is no second copy of the constraints to keep in step.
  * Decoding happens before the handler runs, so `A` is already valid — including
  * every Iron refinement on it. Likewise the output schema is derived from what
  * the handler returns (see [[ToolOutput]]).
  *
  * A handler that fails comes back as an `isError` result naming the tool, never
  * as a protocol error: the model can read it and try again.
  */
final case class McpTool[F[_]](
    definition: Tool,
    invoke: CallToolParams => F[CallToolResult | InputRequiredResult],
    emitting: Option[(CallToolParams, Emit[F]) => F[CallToolResult | InputRequiredResult]]
):
  /** Runs the tool, giving it `emit` if it reports progress. */
  def run(params: CallToolParams, emit: Emit[F]): F[CallToolResult | InputRequiredResult] =
    emitting.fold(invoke(params))(_(params, emit))

object McpTool:

  /** `McpTool[Args](name, description) { args => ... }`: the effect and the output type are inferred from the
    * handler.
    */
  def apply[A](
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString] = None,
      annotations: Option[ToolAnnotations] = None
  ): Builder[A] =
    Builder(name, description, title, annotations)

  /** A tool that can stop to ask the client for input (multi round-trip
    * requests): `McpTool.interactive[Args](name, description) { (args, ctx) => ... }`.
    *
    * The handler returns `ctx.ask(state)(key -> Elicit.form[R](...))` to ask, or
    * `Interaction.done(result)` when it is finished. `state` is sealed by the
    * `RequestStateCodec` in scope before it reaches the client, and a retry
    * whose state was altered never reaches the handler.
    */
  def interactive[A](
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString] = None,
      annotations: Option[ToolAnnotations] = None
  ): InteractiveBuilder[A] =
    InteractiveBuilder(name, description, title, annotations)

  /** A tool that reports progress while it runs:
    * `McpTool.reporting[IO, Args](name, description) { (args, emit) => emit.progress(1, Some(3)) >> ... }`.
    *
    * The effect is named up front because the handler's `emit` is typed by it.
    * Progress reaches the client only when it sent a `progressToken` and the
    * transport can deliver notifications (see [[Emit]]).
    */
  def reporting[F[_], A](
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString] = None,
      annotations: Option[ToolAnnotations] = None
  ): ReportingBuilder[F, A] =
    ReportingBuilder(name, description, title, annotations)

  final class ReportingBuilder[F[_], A] private[McpTool] (
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString],
      annotations: Option[ToolAnnotations]
  ):
    def apply[O](
        handler: (A, Emit[F]) => F[O]
    )(using F: ApplicativeThrow[F], decoder: Decoder[A], schema: JsonSchemaOf[A], output: ToolOutput[O]): McpTool[F] =
      val run: (CallToolParams, Emit[F]) => F[CallToolResult | InputRequiredResult] = (params, emit) =>
        decoded(params, decoder) match
          case Right(arguments) =>
            handler(arguments, emit)
              .map(output.result)
              .handleError(error => CallToolResult.failed(s"$name failed: ${error.getMessage}"))
              .widen[CallToolResult | InputRequiredResult]
          case Left(failed) => F.pure(failed)
      McpTool(describe(name, description, title, annotations, schema, output), params => run(params, Emit.none[F]), Some(run))

  final class Builder[A] private[McpTool] (
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString],
      annotations: Option[ToolAnnotations]
  ):
    def apply[F[_], O](
        handler: A => F[O]
    )(using F: ApplicativeThrow[F], decoder: Decoder[A], schema: JsonSchemaOf[A], output: ToolOutput[O]): McpTool[F] =
      McpTool(
        definition = describe(name, description, title, annotations, schema, output),
        invoke = params =>
          decoded(params, decoder) match
            case Right(arguments) =>
              handler(arguments)
                .map(output.result)
                .handleError(error => CallToolResult.failed(s"$name failed: ${error.getMessage}"))
                .widen[CallToolResult | InputRequiredResult]
            case Left(failed) => F.pure(failed),
        emitting = None
      )

  final class InteractiveBuilder[A] private[McpTool] (
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString],
      annotations: Option[ToolAnnotations]
  ):
    def apply[F[_], O](handler: (A, ToolContext) => F[Interaction[O]])(using
        F: ApplicativeThrow[F],
        decoder: Decoder[A],
        schema: JsonSchemaOf[A],
        output: ToolOutput[O],
        codec: RequestStateCodec
    ): McpTool[F] =
      McpTool(
        definition = describe(name, description, title, annotations, schema, output),
        invoke = params =>
          val arguments = params.arguments.getOrElse(JsonObject.empty)
          decoded(params, decoder) match
            case Left(failed) => F.pure(failed)
            case Right(decodedArguments) =>
              params.requestState.traverse(codec.open(name, arguments, _)) match
                case None =>
                  F.pure(
                    CallToolResult.failed(
                      s"$name: requestState failed its integrity check. Call the tool again without requestState to start over."
                    )
                  )
                case Some(state) =>
                  handler(decodedArguments, ToolContext(params, state))
                    .map {
                      case Right(value) => output.result(value)
                      case Left(input)  => outgoing(name, arguments, input, params._meta.clientCapabilities, codec)
                    }
                    .handleError(error => CallToolResult.failed(s"$name failed: ${error.getMessage}")),
        emitting = None
      )

  /** Seals the state, and turns a request the client cannot answer into a tool
    * error: a stateless server has no other channel to reach the user on.
    */
  private def outgoing(
      name: String,
      arguments: JsonObject,
      input: InputRequiredResult,
      capabilities: ClientCapabilities,
      codec: RequestStateCodec
  ): CallToolResult | InputRequiredResult =
    val missing = input.inputRequests.toList
      .flatMap(_.values)
      .flatMap(_.as[InputRequest].toOption)
      .flatMap(InputRequest.needsOf)
      .distinct
      .filterNot(_.satisfiedBy(capabilities))
    if missing.nonEmpty then
      CallToolResult.failed(
        s"$name needs the client to support ${missing.map(described).mkString(" and ")}, which this client did not declare."
      )
    else input.copy(requestState = input.requestState.map(codec.seal(name, arguments, _)))

  private def described(needed: ClientCapabilityNeeded): String = needed match
    case ClientCapabilityNeeded.ElicitationUrl => "URL elicitation"
    case other                                 => other.toString.toLowerCase

  private def describe[A, O](
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString],
      annotations: Option[ToolAnnotations],
      schema: JsonSchemaOf[A],
      output: ToolOutput[O]
  ): Tool =
    Tool(
      name = name,
      inputSchema = schema.schema.as[ObjectSchema].getOrElse(ObjectSchema.empty),
      title = title,
      description = Some(description),
      outputSchema = output.schema,
      annotations = annotations
    )

  /** A refinement violation is the model's mistake to correct, so it comes back
    * as a tool error carrying Iron's own message.
    */
  private def decoded[A](params: CallToolParams, decoder: Decoder[A]): Either[CallToolResult, A] =
    decoder
      .decodeJson(Json.fromJsonObject(params.arguments.getOrElse(JsonObject.empty)))
      .left
      .map(failure => CallToolResult.failed(failure.message))

/** A [[ToolProvider]] over a fixed set of typed tools. Calling a name it does
  * not have raises [[McpException.unknownTool]] (`-32602`).
  */
final class ToolSet[F[_]](tools: List[McpTool[F]], ttlMs: CacheTtlMs = 60000L)(using F: ApplicativeThrow[F])
    extends ToolProvider[F]:

  private val byName: Map[String, McpTool[F]] =
    tools.map(tool => (tool.definition.name: String) -> tool).toMap

  def list(params: ListToolsParams): F[ListToolsResult] =
    F.pure(ListToolsResult(tools.map(_.definition), ttlMs, CacheScope.`private`))

  def call(params: CallToolParams): F[CallToolResult | InputRequiredResult] =
    callEmitting(params, Emit.none[F])

  override def callEmitting(params: CallToolParams, emit: Emit[F]): F[CallToolResult | InputRequiredResult] =
    byName.get(params.name) match
      case Some(tool) => tool.run(params, emit)
      case None       => F.raiseError(McpException.unknownTool(params.name))

object ToolSet:
  def of[F[_]: ApplicativeThrow](tools: McpTool[F]*): ToolSet[F] = ToolSet(tools.toList)
