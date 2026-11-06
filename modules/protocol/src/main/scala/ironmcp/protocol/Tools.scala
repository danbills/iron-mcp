package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

final case class ToolAnnotations(
    title: Option[NonEmptyString] = None,
    readOnlyHint: Option[Boolean] = None,
    destructiveHint: Option[Boolean] = None,
    idempotentHint: Option[Boolean] = None,
    openWorldHint: Option[Boolean] = None
) derives Codec.AsObject

final case class Tool(
    name: ToolName,
    inputSchema: ObjectSchema,
    title: Option[NonEmptyString] = None,
    description: Option[NonEmptyString] = None,
    outputSchema: Option[JsonObject] = None,
    annotations: Option[ToolAnnotations] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[MetaObject] = None
) derives Codec.AsObject

final case class ListToolsParams(
    _meta: RequestMeta,
    cursor: Option[Cursor] = None
) derives Codec.AsObject

final case class ListToolsResult(
    tools: List[Tool],
    ttlMs: CacheTtlMs,
    cacheScope: CacheScope,
    nextCursor: Option[Cursor] = None,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject

final case class CallToolParams(
    name: ToolName,
    _meta: RequestMeta,
    arguments: Option[JsonObject] = None,
    requestState: Option[String] = None,
    inputResponses: Option[Map[String, Json]] = None
) derives Codec.AsObject

final case class CallToolResult(
    content: List[ContentBlock],
    structuredContent: Option[Json] = None,
    isError: Option[Boolean] = None,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject

object CallToolResult:
  def text(value: String): CallToolResult =
    CallToolResult(content = List(ContentBlock.Text(value)), isError = Some(false))

  def structured(value: String, json: Json): CallToolResult =
    CallToolResult(
      content = List(ContentBlock.Text(value)),
      structuredContent = Some(json),
      isError = Some(false)
    )

  /** A tool that failed at its job. This is deliberately a *result*, not a
    * JSON-RPC error: the model is meant to read it and try again.
    */
  def failed(message: String): CallToolResult =
    CallToolResult(content = List(ContentBlock.Text(message)), isError = Some(true))
