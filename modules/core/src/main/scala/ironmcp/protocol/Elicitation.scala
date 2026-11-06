package ironmcp
package protocol

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given

/** One request a server embeds in an `input_required` result: `{method, params}`.
  * The client answers it by putting the bare result under the same key in
  * `inputResponses` on its retry.
  */
final case class InputRequest(method: NonEmptyString, params: Json)

object InputRequest:
  given Codec.AsObject[InputRequest] = Codec.AsObject.derived

  val ElicitMethod: NonEmptyString = "elicitation/create"
  val SampleMethod: NonEmptyString = "sampling/createMessage"
  val RootsMethod: NonEmptyString  = "roots/list"

  /** A form-mode `elicitation/create`. */
  def elicit(params: ElicitFormParams): InputRequest = InputRequest(ElicitMethod, params.asJson)

  /** A `sampling/createMessage`: a completion from the client's model. */
  def sample(params: CreateMessageParams): InputRequest = InputRequest(SampleMethod, params.asJson)

  /** A `roots/list`: the client's filesystem roots. */
  val listRoots: InputRequest = InputRequest(RootsMethod, Json.obj())

  /** The client capability an embedded request needs, when it is one the spec defines. */
  def needs(method: String): Option[ClientCapabilityNeeded] = method match
    case "elicitation/create"     => Some(ClientCapabilityNeeded.Elicitation)
    case "sampling/createMessage" => Some(ClientCapabilityNeeded.Sampling)
    case "roots/list"             => Some(ClientCapabilityNeeded.Roots)
    case _                        => None

/** Form-mode `elicitation/create` params. The requested schema is a flat object
  * of primitive fields, per the spec.
  */
final case class ElicitFormParams(
    message: String,
    requestedSchema: ObjectSchema,
    // Optional on the wire: the spec's own examples sometimes leave it out.
    mode: Option[String] = Some("form")
) derives Codec.AsObject

enum ElicitAction:
  case Accept, Decline, Cancel

object ElicitAction:
  given Encoder[ElicitAction] = action => Json.fromString(action.toString.toLowerCase)
  given Decoder[ElicitAction] = Decoder[String].emap {
    case "accept"  => Right(ElicitAction.Accept)
    case "decline" => Right(ElicitAction.Decline)
    case "cancel"  => Right(ElicitAction.Cancel)
    case other     => Left(s"not an elicitation action: $other")
  }

/** What the client sends back for one `elicitation/create`. `content` is the
  * user's untrusted input; decode it into a refined type before using it.
  */
final case class ElicitResult(action: ElicitAction, content: Option[JsonObject] = None) derives Codec.AsObject
