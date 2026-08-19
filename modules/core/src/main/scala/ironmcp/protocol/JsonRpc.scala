package ironmcp
package protocol

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*

/** JSON-RPC 2.0 as MCP constrains it: ids are string or number (never null on a
  * request), and every message carries `"jsonrpc": "2.0"` exactly.
  *
  * All codecs here are hand-written rather than derived, because the wire shape
  * is a tagged union discriminated by *field presence* — `id` + `method` is a
  * request, `method` alone is a notification, `result` xor `error` is a
  * response. No derivation scheme expresses that, and no reflection is used.
  */
object JsonRpc:

  val Version: String = "2.0"

/** A JSON-RPC id. The spec permits a string or a number; we keep them distinct
  * rather than collapsing to `String`, because a client that sent `1` must get
  * `1` back, not `"1"`.
  */
enum RequestId:
  case Num(value: Long)
  case Text(value: NonEmptyString)

object RequestId:
  given Encoder[RequestId] =
    case RequestId.Num(v)  => Json.fromLong(v)
    case RequestId.Text(v) => Json.fromString(v)

  given Decoder[RequestId] = Decoder.instance { cursor =>
    cursor.as[Long].map(RequestId.Num.apply).orElse {
      cursor.as[String].flatMap { raw =>
        raw.refineEither[Not[Empty]] match {
          case Right(text) => Right(RequestId.Text(text))
          case Left(why)   => Left(DecodingFailure(why, cursor.history))
        }
      }
    }
  }

/** Error codes: the five JSON-RPC standards plus the MCP-specific ones. */
enum ErrorCode(val code: Int):
  case ParseError     extends ErrorCode(-32700)
  case InvalidRequest extends ErrorCode(-32600)
  case MethodNotFound extends ErrorCode(-32601)
  case InvalidParams  extends ErrorCode(-32602)
  case InternalError  extends ErrorCode(-32603)

  // MCP, revision 2026-07-28
  case HeaderMismatch                  extends ErrorCode(-32020)
  case MissingRequiredClientCapability extends ErrorCode(-32021)
  case UnsupportedProtocolVersion      extends ErrorCode(-32022)

  // MCP, revision 2025-11-25
  case UrlElicitationRequired extends ErrorCode(-32042)

  /** Anything else a server chooses to define. */
  case Other(rawCode: Int) extends ErrorCode(rawCode)

object ErrorCode:
  /** `values` is not generated for an enum with a parameterised case, so the
    * specified codes are listed here and anything else round-trips as `Other`.
    */
  private val specified: List[ErrorCode] = List(
    ParseError,
    InvalidRequest,
    MethodNotFound,
    InvalidParams,
    InternalError,
    HeaderMismatch,
    MissingRequiredClientCapability,
    UnsupportedProtocolVersion,
    UrlElicitationRequired
  )

  def fromInt(value: Int): ErrorCode =
    specified.find(_.code == value).getOrElse(Other(value))

  given Encoder[ErrorCode] = code => Json.fromInt(code.code)
  given Decoder[ErrorCode] = Decoder[Int].map(fromInt)

final case class JsonRpcError(
    code: ErrorCode,
    message: NonEmptyString,
    data: Option[Json] = None
)

object JsonRpcError:
  given Encoder[JsonRpcError] = error =>
    Json.obj(
      "code"    -> error.code.asJson,
      "message" -> Json.fromString(error.message)
    ).deepMerge(error.data.fold(Json.obj())(d => Json.obj("data" -> d)))

  given Decoder[JsonRpcError] = Decoder.instance: cursor =>
    for
      code    <- cursor.get[ErrorCode]("code")
      message <- cursor.get[NonEmptyString]("message")
      data    <- cursor.get[Option[Json]]("data")
    yield JsonRpcError(code, message, data)

/** Anything that can be decoded off the wire. */
enum JsonRpcMessage:
  case Request(id: RequestId, method: NonEmptyString, params: Option[Json])
  case Notification(method: NonEmptyString, params: Option[Json])
  case Success(id: RequestId, result: Json)
  case Failure(id: Option[RequestId], error: JsonRpcError)

object JsonRpcMessage:

  private val versionField = "jsonrpc" -> Json.fromString(JsonRpc.Version)

  given Encoder[JsonRpcMessage] =
    case JsonRpcMessage.Request(id, method, params) =>
      Json
        .obj(versionField, "id" -> id.asJson, "method" -> Json.fromString(method))
        .deepMerge(params.fold(Json.obj())(p => Json.obj("params" -> p)))
    case JsonRpcMessage.Notification(method, params) =>
      Json
        .obj(versionField, "method" -> Json.fromString(method))
        .deepMerge(params.fold(Json.obj())(p => Json.obj("params" -> p)))
    case JsonRpcMessage.Success(id, result) =>
      Json.obj(versionField, "id" -> id.asJson, "result" -> result)
    case JsonRpcMessage.Failure(id, error) =>
      Json.obj(versionField, "id" -> id.fold(Json.Null)(_.asJson), "error" -> error.asJson)

  given Decoder[JsonRpcMessage] = Decoder.instance: cursor =>
    for
      version <- cursor.get[String]("jsonrpc")
      _       <- Either.cond(
                   version == JsonRpc.Version,
                   (),
                   DecodingFailure(s"unsupported jsonrpc version: $version", cursor.history)
                 )
      id      <- cursor.get[Option[RequestId]]("id")
      method  <- cursor.get[Option[NonEmptyString]]("method")
      message <- (id, method) match
                   case (Some(i), Some(m)) => cursor.get[Option[Json]]("params").map(JsonRpcMessage.Request(i, m, _))
                   case (None, Some(m))    => cursor.get[Option[Json]]("params").map(JsonRpcMessage.Notification(m, _))
                   case (maybeId, None) =>
                     cursor.get[Option[JsonRpcError]]("error").flatMap:
                       case Some(error) => Right(JsonRpcMessage.Failure(maybeId, error))
                       case None =>
                         (maybeId, cursor.get[Json]("result")) match
                           case (Some(i), Right(result)) => Right(JsonRpcMessage.Success(i, result))
                           case _ =>
                             Left(DecodingFailure("message has neither result nor error", cursor.history))
    yield message
