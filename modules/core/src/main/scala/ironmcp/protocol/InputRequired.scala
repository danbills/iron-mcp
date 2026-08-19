package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

/** A server saying "I cannot answer until the client does something for me" —
  * sampling, elicitation, or a roots listing.
  *
  * Only `tools/call`, `prompts/get` and `resources/read` may return this, which
  * is why it is a distinct type rather than a flag on every result: the
  * dispatcher can only produce it where the spec allows it.
  */
final case class InputRequiredResult(
    inputRequests: Option[Map[String, Json]] = None,
    requestState: Option[String] = None,
    resultType: ResultType = ResultType.InputRequired,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject

/** The three results a client can hand back. */
enum ClientCapabilityNeeded:
  case Sampling, Elicitation, Roots

object ClientCapabilityNeeded:
  extension (self: ClientCapabilityNeeded)
    def satisfiedBy(capabilities: ClientCapabilities): Boolean = self match
      case ClientCapabilityNeeded.Sampling    => capabilities.sampling.isDefined
      case ClientCapabilityNeeded.Elicitation => capabilities.elicitation.isDefined
      case ClientCapabilityNeeded.Roots       => capabilities.roots.isDefined
