package ironmcp
package protocol

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.circe.given

final case class ToolsCapability(listChanged: Option[Boolean] = None) derives Codec.AsObject
final case class PromptsCapability(listChanged: Option[Boolean] = None) derives Codec.AsObject
final case class ResourcesCapability(
    subscribe: Option[Boolean] = None,
    listChanged: Option[Boolean] = None
) derives Codec.AsObject

final case class ServerCapabilities(
    tools: Option[ToolsCapability] = None,
    resources: Option[ResourcesCapability] = None,
    prompts: Option[PromptsCapability] = None,
    completions: Option[JsonObject] = None,
    logging: Option[JsonObject] = None,
    experimental: Option[Map[String, JsonObject]] = None,
    extensions: Option[Map[String, JsonObject]] = None
) derives Codec.AsObject

object ServerCapabilities:
  val none: ServerCapabilities = ServerCapabilities()

final case class SamplingCapability(
    context: Option[JsonObject] = None,
    tools: Option[JsonObject] = None
) derives Codec.AsObject

final case class ElicitationCapability(
    form: Option[JsonObject] = None,
    url: Option[JsonObject] = None
) derives Codec.AsObject

final case class ClientCapabilities(
    roots: Option[JsonObject] = None,
    sampling: Option[SamplingCapability] = None,
    elicitation: Option[ElicitationCapability] = None,
    experimental: Option[Map[String, JsonObject]] = None,
    extensions: Option[Map[String, JsonObject]] = None
) derives Codec.AsObject

object ClientCapabilities:
  val none: ClientCapabilities = ClientCapabilities()

  /** Capabilities a server may demand before it will serve a request; a miss
    * is reported as `-32021` with the requirement attached.
    */
  extension (self: ClientCapabilities)
    def supportsSampling: Boolean    = self.sampling.isDefined
    def supportsElicitation: Boolean = self.elicitation.isDefined
    def supportsRoots: Boolean       = self.roots.isDefined
