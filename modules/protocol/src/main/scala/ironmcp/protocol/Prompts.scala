package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

final case class PromptArgument(
    name: NonEmptyString,
    title: Option[NonEmptyString] = None,
    description: Option[NonEmptyString] = None,
    required: Option[Boolean] = None
) derives Codec.AsObject

final case class Prompt(
    name: NonEmptyString,
    title: Option[NonEmptyString] = None,
    description: Option[NonEmptyString] = None,
    arguments: Option[List[PromptArgument]] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[MetaObject] = None
) derives Codec.AsObject

final case class PromptMessage(role: Role, content: ContentBlock) derives Codec.AsObject

final case class ListPromptsParams(_meta: RequestMeta, cursor: Option[Cursor] = None) derives Codec.AsObject

final case class ListPromptsResult(
    prompts: List[Prompt],
    ttlMs: CacheTtlMs,
    cacheScope: CacheScope,
    nextCursor: Option[Cursor] = None,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject

final case class GetPromptParams(
    name: NonEmptyString,
    _meta: RequestMeta,
    arguments: Option[Map[String, String]] = None,
    requestState: Option[String] = None,
    inputResponses: Option[Map[String, Json]] = None
) derives Codec.AsObject

final case class GetPromptResult(
    messages: List[PromptMessage],
    description: Option[NonEmptyString] = None,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject
