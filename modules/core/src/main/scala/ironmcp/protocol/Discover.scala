package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

/** `server/discover` replaces the `initialize` handshake. It is cacheable and
  * carries no session: a client may call it once, cache the answer for `ttlMs`,
  * and never call it again.
  */
final case class DiscoverParams(_meta: RequestMeta) derives Codec.AsObject

final case class DiscoverResult(
    supportedVersions: List[ProtocolVersion],
    capabilities: ServerCapabilities,
    ttlMs: Long,
    cacheScope: CacheScope,
    instructions: Option[NonEmptyString] = None,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject
