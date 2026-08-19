package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

final case class ResourceTemplate(
    uriTemplate: NonEmptyString,
    name: NonEmptyString,
    title: Option[NonEmptyString] = None,
    description: Option[NonEmptyString] = None,
    mimeType: Option[MimeType] = None,
    annotations: Option[Annotations] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[MetaObject] = None
) derives Codec.AsObject

final case class ListResourcesParams(_meta: RequestMeta, cursor: Option[Cursor] = None) derives Codec.AsObject

final case class ListResourcesResult(
    resources: List[Resource],
    ttlMs: CacheTtlMs,
    cacheScope: CacheScope,
    nextCursor: Option[Cursor] = None,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject

final case class ListResourceTemplatesResult(
    resourceTemplates: List[ResourceTemplate],
    ttlMs: CacheTtlMs,
    cacheScope: CacheScope,
    nextCursor: Option[Cursor] = None,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject

final case class ReadResourceParams(
    uri: Uri,
    _meta: RequestMeta,
    requestState: Option[String] = None,
    inputResponses: Option[Map[String, Json]] = None
) derives Codec.AsObject

final case class ReadResourceResult(
    contents: List[ResourceContents],
    ttlMs: CacheTtlMs,
    cacheScope: CacheScope,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject
