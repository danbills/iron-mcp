package ironmcp
package apps

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.server.McpTool

/** MCP Apps (`io.modelcontextprotocol/ui`, Handbook §7.4): a tool names an
  * HTML View, served as a `ui://` resource, that an Apps-capable host renders
  * next to the conversation. The View gets the tool's `structuredContent`, and
  * can call the server's tools through the host.
  *
  * A host that does not support Apps ignores all of this and sees ordinary
  * tools and resources, so nothing here changes what a plain client gets.
  */
object McpApps:

  /** The extension's identifier, under `capabilities.extensions`. */
  val ExtensionId: String = "io.modelcontextprotocol/ui"

  /** The media type of a View resource. */
  val MimeType: ironmcp.protocol.MimeType = "text/html;profile=mcp-app"

  /** `_meta.ui` on tools and resources. */
  val UiKey: MetaKey = "ui"

  /** The flat key older hosts read the View's URI from; ext-apps writes both. */
  val LegacyResourceUriKey: MetaKey = "ui/resourceUri"

  /** Whether the request's client declared it can render Views. */
  def clientSupports(capabilities: ClientCapabilities): Boolean =
    capabilities.extensions
      .flatMap(_.get(ExtensionId))
      .flatMap(_("mimeTypes"))
      .flatMap(_.asArray)
      .exists(_.contains(Json.fromString(MimeType)))

  /** For `McpServer(extensions = Some(Map(McpApps.serverExtension)))`, to
    * announce Apps support in `server/discover`. Hosts do not require it: they
    * look for `_meta.ui` on the tools.
    */
  val serverExtension: (String, JsonObject) = ExtensionId -> JsonObject.empty

/** `ui://...`: the scheme the extension reserves for Views. */
type UiUri = String :| (StartWith["ui://"] DescribedAs "A View resource URI, starting with ui://")

/** Who may call a tool. `App` alone keeps it from the model: the host lets
  * only the View call it.
  *
  * That is a convention hosts follow, not access control. The server still
  * lists the tool and runs it for any client that calls it, so a tool that
  * does something consequential needs its own authorization check, whatever
  * its visibility.
  */
enum Visibility:
  case Model, App

object Visibility:
  given Encoder[Visibility] = v => Json.fromString(v.toString.toLowerCase)
  given Decoder[Visibility] = Decoder[String].emap {
    case "model" => Right(Visibility.Model)
    case "app"   => Right(Visibility.App)
    case other   => Left(s"not a visibility: $other")
  }

/** `_meta.ui` on a tool. */
final case class UiToolMeta(resourceUri: Option[String] = None, visibility: Option[List[Visibility]] = None) derives Codec.AsObject

/** Which origins a View may reach. Each list left out means none: the default
  * sandbox allows no network access.
  */
final case class UiCsp(
    connectDomains: Option[List[String]] = None,
    resourceDomains: Option[List[String]] = None,
    frameDomains: Option[List[String]] = None,
    baseUriDomains: Option[List[String]] = None
) derives Codec.AsObject

/** Browser features a View asks the host to grant its iframe (the iframe's
  * `allow` attribute). Each present field is a request; the host decides.
  */
final case class UiPermissions(
    camera: Option[JsonObject] = None,
    microphone: Option[JsonObject] = None,
    geolocation: Option[JsonObject] = None,
    clipboardWrite: Option[JsonObject] = None
) derives Codec.AsObject

object UiPermissions:
  private val requested = Some(JsonObject.empty)
  val camera: UiPermissions         = UiPermissions(camera = requested)
  val microphone: UiPermissions     = UiPermissions(microphone = requested)
  val geolocation: UiPermissions    = UiPermissions(geolocation = requested)
  val clipboardWrite: UiPermissions = UiPermissions(clipboardWrite = requested)

  extension (a: UiPermissions)
    /** Both sets of requests: `UiPermissions.microphone ++ UiPermissions.clipboardWrite`. */
    def ++(b: UiPermissions): UiPermissions =
      UiPermissions(a.camera.orElse(b.camera), a.microphone.orElse(b.microphone), a.geolocation.orElse(b.geolocation), a.clipboardWrite.orElse(b.clipboardWrite))

/** `_meta.ui` on a View resource. `domain` is host-specific: see the host's
  * documentation before setting it.
  */
final case class UiResourceMeta(
    csp: Option[UiCsp] = None,
    permissions: Option[UiPermissions] = None,
    domain: Option[String] = None,
    prefersBorder: Option[Boolean] = None
) derives Codec.AsObject

extension [F[_]](tool: McpTool[F])
  /** Links the tool to the View at `resourceUri`. With no `visibility`, the
    * host's default applies: both the model and the View may call it.
    */
  def withUi(resourceUri: UiUri, visibility: Visibility*): McpTool[F] =
    val ui = UiToolMeta(Some(resourceUri), Option.when(visibility.nonEmpty)(visibility.toList.distinct))
    val meta = tool.definition._meta.getOrElse(MetaObject.empty) ++ Map(
      McpApps.UiKey                -> ui.asJson.deepDropNullValues,
      McpApps.LegacyResourceUriKey -> Json.fromString(resourceUri)
    )
    tool.copy(definition = tool.definition.copy(_meta = Some(meta)))

  /** Who may call the tool, for a tool with no View of its own: often
    * `Visibility.App`, a tool only Views call (a View polling its server).
    */
  def withVisibility(visibility: Visibility, more: Visibility*): McpTool[F] =
    val ui   = UiToolMeta(visibility = Some((visibility +: more).toList.distinct))
    val meta = tool.definition._meta.getOrElse(MetaObject.empty) ++ Map(McpApps.UiKey -> ui.asJson.deepDropNullValues)
    tool.copy(definition = tool.definition.copy(_meta = Some(meta)))
