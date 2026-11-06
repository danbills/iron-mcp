# MCP Apps with iron-mcp

Companion to **Chapter 7, §7.4 of the *MCP Engineering Handbook*** ("Building Your First MCP App").

An MCP App is a tool linked to an HTML **View**. An Apps-capable host renders the View next to the conversation and hands it the tool's result. The View can then call the server's tools through the host. There are two halves:

- `iron-mcp-apps`, the server side of the extension (`io.modelcontextprotocol/ui`): tools linked to Views, and the Views served as resources. It is pure protocol, so it cross-builds to the JVM and Scala Native like core.
- `iron-mcp-apps-view`, the View side, in Scala.js: the View's connection to its host.

```scala
libraryDependencies += "io.github.danbills" %% "iron-mcp-apps" % "<version>"       // the server
libraryDependencies += "io.github.danbills" %% "iron-mcp-apps-view" % "<version>"  // the View (a Scala.js project)
```

## Linking a tool to a View

`withUi` writes `_meta.ui.resourceUri`, plus the flat `_meta["ui/resourceUri"]` mirror that older hosts read (ext-apps writes both). A `UiUri` must start with `ui://`, and that is checked at compile time.

```scala mdoc:silent
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.protocol.Implementation
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*

final case class NoArgs() derives Decoder, JsonSchemaOf
final case class Reading(celsius: Double) derives Encoder.AsObject, JsonSchemaOf

val view: UiUri = "ui://thermo/view.html"

val read = McpTool[NoArgs]("read_temperature", "Reads the thermometer.")(_ => IO.pure(Reading(21.5)))
  .withUi(view)

val refresh = McpTool[NoArgs]("refresh_temperature", "Refreshes the View.")(_ => IO.pure(Reading(21.6)))
  .withUi(view, Visibility.App)
```

The model reads the text block. The View gets `structuredContent`, which is derived from the result type like any other iron-mcp tool, so the data the View renders has a declared schema.

## Serving the View

`UiResources` serves self-contained HTML documents. It lists and reads them as `text/html;profile=mcp-app`, together with their `_meta.ui` (CSP, `prefersBorder`). Any other URI gets `-32002`.

```scala mdoc:silent
val thermo = McpServer[IO](
  info = Implementation(name = "thermo", version = "1.0.0"),
  tools = Some(ToolSet.of(read, refresh)),
  resources = Some(UiResources.of[IO](
    UiResource(view, "Thermometer", "<!DOCTYPE html><html>...</html>",
      meta = UiResourceMeta(csp = Some(UiCsp(connectDomains = Some(List("https://api.example.com"))))))
  ))
)
```

The default sandbox gives a View no network access. Inline its scripts and styles, or list the origins it needs in `UiCsp`.

## The View side, in Scala.js

A View never talks to the MCP server. It talks to the host, in JSON-RPC 2.0 over `window.parent.postMessage` (ext-apps' protocol `2026-01-26`), and the host relays what needs relaying. `McpAppView` is that connection, on cats-effect and fs2, with the server's own types from `iron-mcp-protocol` (`CallToolResult`, `ContentBlock`, `Implementation`):

```scala
import cats.effect.{IO, IOApp}
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.protocol.Implementation

object ThermoView extends IOApp.Simple:
  def run: IO[Unit] =
    val views = for
      view <- McpAppView.inWindow[IO](Implementation(name = "Thermometer", version = "1.0.0"))
      _    <- HostTheming.follow(view)   // the host's theme, CSS variables and fonts, kept current
      _    <- AutoResize(view)           // ui/notifications/size-changed as the content grows
    yield view
    views.use { view =>
      view.events
        .evalMap {
          case HostEvent.ToolResult(result) => render(result.structuredAs[Reading])
          case _                            => IO.unit
        }
        .compile
        .drain
    }

  // The refresh button: an app-only tool, called through the host.
  def refresh(view: McpAppView[IO]): IO[Unit] =
    view.callServerTool("refresh_temperature").flatMap(result => render(result.structuredAs[Reading]))
```

- `McpAppView.inWindow` does the handshake (`ui/initialize`, then `ui/notifications/initialized`) before it yields. `hostInfo`, `hostCapabilities` and `hostContext` (a `Signal`, kept current by `host-context-changed`) come from it.
- `events` is the tool call's input (and partial input while it streams), result and cancellation, buffered from before the handshake so the View cannot miss the result.
- The View can also call `readServerResource`, `openLink`, `downloadFile`, `sendMessage` (into the conversation, as the user), `updateModelContext`, `requestDisplayMode`, `requestTeardown` and `log`. A host's JSON-RPC error raises `McpAppViewError`.
- Pass `onTeardown` to save state before the host's `ui/resource-teardown` is answered.
- `structuredContent` is untrusted input. `structuredAs[A]` decodes it, and decoding is the validation: render only what decodes, and write values with `textContent`, never `innerHTML`.
- `HostPort.inMemory` tests a View without a browser: the test plays the host.

Share the types the View renders with the server: put `Reading` in a JVM and JS `crossProject` that depends on `iron-mcp-protocol`, with `derives Codec.AsObject, JsonSchemaOf`. Then the server's output schema, its encoder and the View's decoder are one definition.

### Packaging the View

The default sandbox loads nothing from the network, so the View ships as one HTML document with the linked JS inline. Let sbt put the linked script among the server's resources, and build the page when the server starts, with `UiHtml.inlineScript` (from `iron-mcp-apps`):

```scala
// build.sbt
lazy val view = project
  .enablePlugins(ScalaJSPlugin)
  .settings(scalaJSUseMainModuleInitializer := true)

lazy val server = project.settings(
  Compile / resourceGenerators += Def.task {
    Def.uncached {   // sbt 2: a task returning files is not cached
      val _      = (view / Compile / fullLinkJS).value
      val linked = (view / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / "main.js"
      val out    = (Compile / resourceManaged).value / "thermo" / "view.js"
      IO.copyFile(linked, out)
      Seq(out)
    }
  }.taskValue
)
```

```scala
// the server: a template with <!-- mcp-app-script --> where the script goes
val html = UiHtml.inlineScript(resource("thermo/view.html"), resource("thermo/view.js"))
UiResources.of[IO](UiResource(view, "Thermometer", html))
```

The linked script is large: about 3 MB for a View on cats-effect, fs2 and circe, unminified under sbt 2 (danbills/iron-mcp#1).

### Without Scala.js

The protocol underneath is small, so a hand-written View works too:

1. Send `ui/initialize` (`appInfo`, `appCapabilities`, `protocolVersion`) and wait for the host's reply.
2. Send `ui/notifications/initialized`.
3. Render each `ui/notifications/tool-result`, which carries a `CallToolResult`.
4. Call server tools with `tools/call` through the host.

## App-only is a host convention, not access control

`Visibility.App` tells an Apps-capable host to keep the tool away from the model. The server still lists the tool to every client and runs it for anyone who calls it, and a client without Apps support ignores the metadata entirely. That is fine for a read-only refresh. A tool that does something consequential needs its own authorization at execution, whatever its visibility (see `ScopedTools` and the per-tool scope policy in the OAuth guide).

## Knowing the client

`McpApps.clientSupports(ctx.clientCapabilities)` tells you whether the request's client declared `io.modelcontextprotocol/ui` with the `mcp-app` media type. Use it to decide whether a tool's text block should carry everything, or can leave the details to the View. To announce Apps support in `server/discover`, pass `extensions = Some(Map(McpApps.serverExtension))` to `McpServer`. Hosts don't require it: they look for `_meta.ui` on the tools.

## Seeing it

A plain MCP client sees ordinary tools and resources. To see a View, connect the server to an Apps-capable host, or use the example host in the ext-apps repository (`examples/basic-host`).
