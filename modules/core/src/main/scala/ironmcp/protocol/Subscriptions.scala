package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

/** What a client wants to hear about. In a stateless world the server holds no
  * subscription state itself; the id it hands back is what the client presents
  * to a streaming transport.
  */
final case class SubscriptionFilter(
    toolsListChanged: Option[Boolean] = None,
    promptsListChanged: Option[Boolean] = None,
    resourcesListChanged: Option[Boolean] = None,
    resourceSubscriptions: Option[List[Uri]] = None
) derives Codec.AsObject

final case class SubscriptionsListenParams(
    notifications: SubscriptionFilter,
    _meta: RequestMeta
) derives Codec.AsObject

/** The subscription id is mandatory here, so it is a plain field rather than an
  * optional buried in `_meta`; the encoder puts it where the spec wants it.
  */
final case class SubscriptionsListenResult(
    subscriptionId: RequestId,
    serverInfo: Option[Implementation] = None,
    resultType: ResultType = ResultType.Complete
)

object SubscriptionsListenResult:
  import io.circe.syntax.*

  given Encoder[SubscriptionsListenResult] = result =>
    Json.obj(
      "resultType" -> result.resultType.asJson,
      "_meta" -> ResultMeta(
        serverInfo = result.serverInfo,
        subscriptionId = Some(result.subscriptionId)
      ).asJson
    )

  given Decoder[SubscriptionsListenResult] = Decoder.instance { cursor =>
    for
      meta       <- cursor.get[ResultMeta]("_meta")
      resultType <- cursor.get[Option[ResultType]]("resultType")
      id <- meta.subscriptionId.toRight(
              DecodingFailure("subscriptions/listen result has no subscriptionId", cursor.history)
            )
    yield SubscriptionsListenResult(id, meta.serverInfo, resultType.getOrElse(ResultType.Complete))
  }
