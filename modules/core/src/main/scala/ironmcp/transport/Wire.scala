package ironmcp
package transport

import io.circe.{Json, parser}
import io.circe.syntax.*
import ironmcp.protocol.*

/** Serialisation rules that apply to every transport.
  *
  * `deepDropNullValues` is load-bearing: Circe encodes `None` as `null`, but
  * MCP distinguishes an absent optional field from a null one, and several
  * hosts reject `null` where they expect omission.
  */
object Wire:

  def encode(message: JsonRpcMessage): String =
    val json = message.asJson.deepDropNullValues
    message match
      // JSON-RPC 2.0: an error whose request id could not be read still carries "id": null, which dropping nulls removed
      case JsonRpcMessage.Failure(None, _) => json.mapObject(_.add("id", Json.Null)).noSpaces
      case _                               => json.noSpaces

  def decode(raw: String): Either[JsonRpcError, JsonRpcMessage] =
    parser.parse(raw) match
      case Left(failure) =>
        Left(JsonRpcError(ErrorCode.ParseError, nonEmpty(s"invalid JSON: ${failure.message}")))
      case Right(json) =>
        json.as[JsonRpcMessage].left.map { failure =>
          JsonRpcError(ErrorCode.InvalidRequest, nonEmpty(failure.message), Some(failure.message.asJson))
        }

  /** Errors that arise before an id is known are reported against a null id,
    * which JSON-RPC permits precisely for this case.
    */
  def errorResponse(error: JsonRpcError): String =
    encode(JsonRpcMessage.Failure(None, error))

  /** A decoder's message, which the protocol requires non-empty. */
  private[transport] def nonEmpty(value: String): NonEmptyString =
    (if value.isEmpty then "unspecified error" else value).asInstanceOf[NonEmptyString]

  def json(value: Json): String = value.deepDropNullValues.noSpaces
