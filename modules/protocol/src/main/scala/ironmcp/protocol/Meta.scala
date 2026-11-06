package ironmcp
package protocol

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given

/** `_meta` is a free-form map, but its *keys* are not free-form: the spec gives
  * them a grammar and reserves the `modelcontextprotocol` / `mcp` prefixes.
  * Encoding that as [[MetaKey]] means an ill-formed key cannot be written in
  * Scala and will not decode off the wire.
  */
type MetaObject = Map[MetaKey, Json]

object MetaObject:
  val empty: MetaObject = Map.empty

  given KeyEncoder[MetaKey] = KeyEncoder.encodeKeyString.contramap(identity)
  given KeyDecoder[MetaKey] = KeyDecoder.instance(_.refineOption[MetaKeyC])

  /** Reads an optional entry, failing the decode if it is present but malformed. */
  def read[A: Decoder](meta: MetaObject, key: MetaKey): Decoder.Result[Option[A]] =
    meta.get(key) match
      case None        => Right(None)
      case Some(value) => value.as[A].map(Some(_))

  def require[A: Decoder](meta: MetaObject, key: MetaKey, history: List[CursorOp]): Decoder.Result[A] =
    meta.get(key) match
      case None        => Left(DecodingFailure(s"_meta is missing the required key '$key'", history))
      case Some(value) => value.as[A]

/** Keys the specification reserves for protocol-level metadata. */
object ReservedMeta:
  val ProtocolVersion: MetaKey    = "io.modelcontextprotocol/protocolVersion"
  val ClientInfo: MetaKey         = "io.modelcontextprotocol/clientInfo"
  val ClientCapabilities: MetaKey = "io.modelcontextprotocol/clientCapabilities"
  val LogLevel: MetaKey           = "io.modelcontextprotocol/logLevel"
  val ServerInfo: MetaKey         = "io.modelcontextprotocol/serverInfo"
  val SubscriptionId: MetaKey     = "io.modelcontextprotocol/subscriptionId"
  val ProgressToken: MetaKey      = "progressToken"

  val requestKeys: Set[MetaKey] =
    Set(ProtocolVersion, ClientInfo, ClientCapabilities, LogLevel, ProgressToken)

  val resultKeys: Set[MetaKey] = Set(ServerInfo, SubscriptionId)

/** The envelope every 2026-07-28 request must carry.
  *
  * `protocolVersion` and `clientCapabilities` are **required**: this is the
  * revision's replacement for the `initialize` handshake, so a client that
  * omits them gets an error rather than a best-effort guess.
  */
final case class RequestMeta(
    protocolVersion: ProtocolVersion,
    clientCapabilities: ClientCapabilities,
    clientInfo: Option[Implementation] = None,
    logLevel: Option[LoggingLevel] = None,
    progressToken: Option[ProgressToken] = None,
    extra: MetaObject = MetaObject.empty
)

object RequestMeta:
  import MetaObject.given

  given Encoder[RequestMeta] = meta =>
    val reserved = Map[MetaKey, Json](
      ReservedMeta.ProtocolVersion    -> Json.fromString(meta.protocolVersion),
      ReservedMeta.ClientCapabilities -> meta.clientCapabilities.asJson
    )
      ++ meta.clientInfo.map(ReservedMeta.ClientInfo -> _.asJson)
      ++ meta.logLevel.map(ReservedMeta.LogLevel -> _.asJson)
      ++ meta.progressToken.map(ReservedMeta.ProgressToken -> _.asJson)
    (meta.extra ++ reserved).asJson

  given Decoder[RequestMeta] = Decoder.instance { cursor =>
    for
      all      <- cursor.as[MetaObject]
      version  <- MetaObject.require[ProtocolVersion](all, ReservedMeta.ProtocolVersion, cursor.history)
      caps     <- MetaObject.require[ClientCapabilities](all, ReservedMeta.ClientCapabilities, cursor.history)
      info     <- MetaObject.read[Implementation](all, ReservedMeta.ClientInfo)
      level    <- MetaObject.read[LoggingLevel](all, ReservedMeta.LogLevel)
      progress <- MetaObject.read[ProgressToken](all, ReservedMeta.ProgressToken)
    yield RequestMeta(version, caps, info, level, progress, all -- ReservedMeta.requestKeys)
  }

/** Result-side metadata. */
final case class ResultMeta(
    serverInfo: Option[Implementation] = None,
    subscriptionId: Option[RequestId] = None,
    extra: MetaObject = MetaObject.empty
)

object ResultMeta:
  import MetaObject.given

  val empty: ResultMeta = ResultMeta()

  given Encoder[ResultMeta] = meta =>
    val reserved = meta.serverInfo.map(ReservedMeta.ServerInfo -> _.asJson).toMap
      ++ meta.subscriptionId.map(ReservedMeta.SubscriptionId -> _.asJson).toMap
    (meta.extra ++ reserved).asJson

  given Decoder[ResultMeta] = Decoder.instance { cursor =>
    for
      all  <- cursor.as[MetaObject]
      info <- MetaObject.read[Implementation](all, ReservedMeta.ServerInfo)
      sub  <- MetaObject.read[RequestId](all, ReservedMeta.SubscriptionId)
    yield ResultMeta(info, sub, all -- ReservedMeta.resultKeys)
  }

/** Notification-side metadata. */
final case class NotificationMeta(
    subscriptionId: Option[RequestId] = None,
    extra: MetaObject = MetaObject.empty
)

object NotificationMeta:
  import MetaObject.given

  val empty: NotificationMeta = NotificationMeta()

  given Encoder[NotificationMeta] = meta =>
    (meta.extra ++ meta.subscriptionId.map(ReservedMeta.SubscriptionId -> _.asJson).toMap).asJson

  given Decoder[NotificationMeta] = Decoder.instance { cursor =>
    for
      all <- cursor.as[MetaObject]
      sub <- MetaObject.read[RequestId](all, ReservedMeta.SubscriptionId)
    yield NotificationMeta(sub, all - ReservedMeta.SubscriptionId)
  }
