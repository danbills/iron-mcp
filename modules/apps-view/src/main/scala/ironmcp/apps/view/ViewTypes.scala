package ironmcp.apps.view

import io.circe.*
import io.circe.syntax.*
import ironmcp.protocol.{Implementation, RequestId, Tool}

/** The types of the MCP Apps View dialect (ext-apps `spec.types.ts`, protocol
  * `2026-01-26`) that are not already MCP types in `ironmcp.protocol`.
  *
  * Outgoing JSON drops absent fields: hosts validate with schemas that accept
  * a missing optional field but not a `null` one.
  */
private def stringEnum[A](values: Array[A], what: String): Codec[A] =
  Codec.from(
    Decoder[String].emap(raw => values.find(_.toString == raw).toRight(s"not a $what: $raw")),
    Encoder[String].contramap(_.toString)
  )

private def dropNulls[A](encoder: Encoder.AsObject[A]): Encoder.AsObject[A] =
  encoder.mapJsonObject(_.filter((_, value) => !value.isNull))

enum Theme:
  case light, dark

object Theme:
  given Codec[Theme] = stringEnum(values, "theme")

enum DisplayMode:
  case `inline`, fullscreen, pip

object DisplayMode:
  given Codec[DisplayMode] = stringEnum(values, "display mode")

enum Platform:
  case web, desktop, mobile

object Platform:
  given Codec[Platform] = stringEnum(values, "platform")

/** `{}` capabilities: present means supported. */
final case class ListChanged(listChanged: Option[Boolean] = None)

object ListChanged:
  given Decoder[ListChanged] = Decoder.derived
  given Encoder.AsObject[ListChanged] = dropNulls(Encoder.AsObject.derived)

/** The content kinds a host accepts in `ui/message` or `ui/update-model-context`. */
final case class Modalities(
    text: Option[JsonObject] = None,
    image: Option[JsonObject] = None,
    audio: Option[JsonObject] = None,
    resource: Option[JsonObject] = None,
    resourceLink: Option[JsonObject] = None,
    structuredContent: Option[JsonObject] = None
)

object Modalities:
  given Decoder[Modalities] = Decoder.derived
  given Encoder.AsObject[Modalities] = dropNulls(Encoder.AsObject.derived)

/** What the host offers the View. Each field's presence is the capability. */
final case class HostCapabilities(
    experimental: Option[JsonObject] = None,
    openLinks: Option[JsonObject] = None,
    downloadFile: Option[JsonObject] = None,
    serverTools: Option[ListChanged] = None,
    serverResources: Option[ListChanged] = None,
    logging: Option[JsonObject] = None,
    sandbox: Option[JsonObject] = None,
    updateModelContext: Option[Modalities] = None,
    message: Option[Modalities] = None,
    sampling: Option[JsonObject] = None
)

object HostCapabilities:
  given Decoder[HostCapabilities] = Decoder.derived
  given Encoder.AsObject[HostCapabilities] = dropNulls(Encoder.AsObject.derived)

/** What the View declares in `ui/initialize`. */
final case class AppCapabilities(
    experimental: Option[JsonObject] = None,
    tools: Option[ListChanged] = None,
    availableDisplayModes: Option[List[DisplayMode]] = None
)

object AppCapabilities:
  given Decoder[AppCapabilities] = Decoder.derived
  given Encoder.AsObject[AppCapabilities] = dropNulls(Encoder.AsObject.derived)

/** `containerDimensions`: a fixed size or a maximum, per axis. The spec's union
  * of objects flattens to four optional fields.
  */
final case class ContainerDimensions(
    width: Option[Double] = None,
    maxWidth: Option[Double] = None,
    height: Option[Double] = None,
    maxHeight: Option[Double] = None
)

object ContainerDimensions:
  given Decoder[ContainerDimensions] = Decoder.derived
  given Encoder.AsObject[ContainerDimensions] = dropNulls(Encoder.AsObject.derived)

final case class DeviceCapabilities(touch: Option[Boolean] = None, hover: Option[Boolean] = None)

object DeviceCapabilities:
  given Decoder[DeviceCapabilities] = Decoder.derived
  given Encoder.AsObject[DeviceCapabilities] = dropNulls(Encoder.AsObject.derived)

final case class SafeAreaInsets(top: Double, right: Double, bottom: Double, left: Double) derives Codec.AsObject

/** The tool call this View instance renders. */
final case class ToolInfo(tool: Tool, id: Option[RequestId] = None)

object ToolInfo:
  given Decoder[ToolInfo] = Decoder.derived
  given Encoder.AsObject[ToolInfo] = dropNulls(Encoder.AsObject.derived)

/** `styles`: the host's CSS custom properties (`--color-background-primary`,
  * ...) and font CSS. A variable the host leaves undefined is left out.
  */
final case class Styles(variables: Map[String, String] = Map.empty, fonts: Option[String] = None)

object Styles:
  given Decoder[Styles] = Decoder.instance: cursor =>
    for
      variables <- cursor.get[Option[Map[String, Option[String]]]]("variables")
      fonts     <- cursor.downField("css").get[Option[String]]("fonts").orElse(Right(None))
    yield Styles(variables.getOrElse(Map.empty).collect { case (key, Some(value)) => key -> value }, fonts)

  given Encoder.AsObject[Styles] = styles =>
    JsonObject(
      "variables" -> styles.variables.asJson,
      "css"       -> styles.fonts.fold(Json.Null)(fonts => Json.obj("fonts" -> fonts.asJson))
    ).filter((_, value) => !value.isNull)

/** The host's context for this View: theme, styles, display mode, sizes,
  * locale and so on, as of `ui/initialize` and every
  * `ui/notifications/host-context-changed` since.
  *
  * Context is advisory, so decoding never fails: a field the View cannot read
  * is absent. `raw` keeps everything the host sent, including keys this type
  * does not name, and changes merge into it key by key, as in ext-apps.
  */
final case class HostContext(
    toolInfo: Option[ToolInfo] = None,
    theme: Option[Theme] = None,
    styles: Option[Styles] = None,
    displayMode: Option[DisplayMode] = None,
    availableDisplayModes: Option[List[DisplayMode]] = None,
    containerDimensions: Option[ContainerDimensions] = None,
    locale: Option[String] = None,
    timeZone: Option[String] = None,
    userAgent: Option[String] = None,
    platform: Option[Platform] = None,
    deviceCapabilities: Option[DeviceCapabilities] = None,
    safeAreaInsets: Option[SafeAreaInsets] = None,
    raw: JsonObject = JsonObject.empty
):
  /** This context with a `host-context-changed` update applied. */
  def merge(update: JsonObject): HostContext =
    HostContext.fromJson(JsonObject.fromIterable(raw.toIterable ++ update.toIterable))

object HostContext:
  val empty: HostContext = HostContext()

  def fromJson(json: JsonObject): HostContext =
    val cursor = Json.fromJsonObject(json).hcursor
    def field[A: Decoder](key: String): Option[A] = cursor.get[Option[A]](key).toOption.flatten
    HostContext(
      toolInfo = field[ToolInfo]("toolInfo"),
      theme = field[Theme]("theme"),
      styles = field[Styles]("styles"),
      displayMode = field[DisplayMode]("displayMode"),
      availableDisplayModes = field[List[DisplayMode]]("availableDisplayModes"),
      containerDimensions = field[ContainerDimensions]("containerDimensions"),
      locale = field[String]("locale"),
      timeZone = field[String]("timeZone"),
      userAgent = field[String]("userAgent"),
      platform = field[Platform]("platform"),
      deviceCapabilities = field[DeviceCapabilities]("deviceCapabilities"),
      safeAreaInsets = field[SafeAreaInsets]("safeAreaInsets"),
      raw = json
    )

  given Decoder[HostContext] = Decoder.decodeJsonObject.map(fromJson)

  /** Writes the typed fields over `raw`, so a context built in code (a test
    * host, say) encodes what it says.
    */
  given Encoder.AsObject[HostContext] = context =>
    val typed = JsonObject(
      "toolInfo"              -> context.toolInfo.asJson,
      "theme"                 -> context.theme.asJson,
      "styles"                -> context.styles.asJson,
      "displayMode"           -> context.displayMode.asJson,
      "availableDisplayModes" -> context.availableDisplayModes.asJson,
      "containerDimensions"   -> context.containerDimensions.asJson,
      "locale"                -> context.locale.asJson,
      "timeZone"              -> context.timeZone.asJson,
      "userAgent"             -> context.userAgent.asJson,
      "platform"              -> context.platform.asJson,
      "deviceCapabilities"    -> context.deviceCapabilities.asJson,
      "safeAreaInsets"        -> context.safeAreaInsets.asJson
    ).filter((_, value) => !value.isNull)
    JsonObject.fromIterable(context.raw.toIterable ++ typed.toIterable)

/** The host's answer to `ui/initialize`. */
final case class InitializeResult(
    protocolVersion: String,
    hostInfo: Implementation,
    hostCapabilities: HostCapabilities,
    hostContext: HostContext
)

object InitializeResult:
  given Decoder[InitializeResult] = Decoder.instance: cursor =>
    for
      version      <- cursor.get[String]("protocolVersion")
      hostInfo     <- cursor.get[Implementation]("hostInfo")
      capabilities <- cursor.get[Option[HostCapabilities]]("hostCapabilities")
      context      <- cursor.get[Option[HostContext]]("hostContext")
    yield InitializeResult(version, hostInfo, capabilities.getOrElse(HostCapabilities()), context.getOrElse(HostContext.empty))

  given Encoder.AsObject[InitializeResult] = Encoder.AsObject.derived
