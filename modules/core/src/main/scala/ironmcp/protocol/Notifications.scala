package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

/** Notifications a server may emit. Each is a plain params record; the method
  * name lives in [[Method]], so the two cannot drift apart.
  */
final case class CancelledParams(
    requestId: RequestId,
    reason: Option[NonEmptyString] = None,
    _meta: Option[NotificationMeta] = None
) derives Codec.AsObject

final case class ProgressParams(
    progressToken: ProgressToken,
    progress: Progress,
    total: Option[Double] = None,
    message: Option[NonEmptyString] = None,
    _meta: Option[NotificationMeta] = None
) derives Codec.AsObject

final case class LoggingMessageParams(
    level: LoggingLevel,
    data: Json,
    logger: Option[NonEmptyString] = None,
    _meta: Option[NotificationMeta] = None
) derives Codec.AsObject

final case class ResourceUpdatedParams(
    uri: Uri,
    _meta: Option[NotificationMeta] = None
) derives Codec.AsObject

final case class ListChangedParams(_meta: Option[NotificationMeta] = None) derives Codec.AsObject

final case class SubscriptionsAcknowledgedParams(
    notifications: SubscriptionFilter,
    _meta: Option[NotificationMeta] = None
) derives Codec.AsObject
