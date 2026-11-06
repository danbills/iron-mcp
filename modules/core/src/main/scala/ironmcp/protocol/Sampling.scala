package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

/** `sampling/createMessage`: the server asks the client's model for a
  * completion. In 2026-07-28 a server never sends it as a request; it embeds
  * it in an `input_required` result (see [[InputRequest.sample]]).
  */
final case class SamplingMessage(role: Role, content: ContentBlock) derives Codec.AsObject

final case class ModelHint(name: Option[String] = None) derives Codec.AsObject

/** Advisory: the client picks the model. Priorities are 0 to 1. */
final case class ModelPreferences(
    hints: Option[List[ModelHint]] = None,
    costPriority: Option[Double] = None,
    speedPriority: Option[Double] = None,
    intelligencePriority: Option[Double] = None
) derives Codec.AsObject

enum IncludeContext:
  case none, thisServer, allServers

object IncludeContext:
  given Encoder[IncludeContext] = value => Json.fromString(value.toString)
  given Decoder[IncludeContext] = Decoder[String].emap(raw =>
    IncludeContext.values.find(_.toString == raw).toRight(s"not an includeContext value: $raw")
  )

final case class CreateMessageParams(
    messages: List[SamplingMessage],
    maxTokens: Int,
    systemPrompt: Option[String] = None,
    modelPreferences: Option[ModelPreferences] = None,
    includeContext: Option[IncludeContext] = None,
    temperature: Option[Double] = None,
    stopSequences: Option[List[String]] = None,
    metadata: Option[JsonObject] = None
) derives Codec.AsObject

/** What the client's model produced. `content` came from a model the server
  * does not control: treat it as untrusted input.
  */
final case class CreateMessageResult(
    role: Role,
    content: ContentBlock,
    model: NonEmptyString,
    stopReason: Option[String] = None
) derives Codec.AsObject
