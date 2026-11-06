# MCP Apps with iron-mcp

Companion to **Chapter 7, §7.4 of the *MCP Engineering Handbook*** ("Building Your First MCP App"), and a port of its `chapter07/health-dashboard` sample (`modules/health`).

An MCP App is a tool linked to an HTML **View**. An Apps-capable host renders the View next to the conversation and hands it the tool's result. The View can then call the server's tools through the host. `iron-mcp-apps` is the extension (`io.modelcontextprotocol/ui`). It is pure protocol, so it cross-builds to the JVM and Scala Native like core.

```scala
libraryDependencies += "io.github.danbills" %% "iron-mcp-apps" % "<version>"
```

## Linking a tool to a View

`withUi` writes `_meta.ui.resourceUri`, plus the flat `_meta["ui/resourceUri"]` mirror that older hosts read (ext-apps writes both). A `UiUri` must start with `ui://`, and that is checked at compile time.

```scala
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

```scala
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

## The View side

ext-apps' `App` class is a TypeScript library, but the protocol underneath is small: JSON-RPC 2.0 over `window.parent.postMessage`. The health dashboard's View (`modules/health/src/main/resources/ironmcp/health/dashboard.html`) speaks it directly, with no bundle:

1. Send `ui/initialize` (`appInfo`, `appCapabilities`, `protocolVersion`) and wait for the host's reply.
2. Send `ui/notifications/initialized`.
3. Render each `ui/notifications/tool-result`, which carries a `CallToolResult`.
4. Call server tools with `tools/call` through the host. The refresh button calls `refresh_health` this way.

Treat `structuredContent` as untrusted input in the View: check its shape before rendering, and write values with `textContent`, never `innerHTML`.

## App-only is a host convention, not access control

`Visibility.App` tells an Apps-capable host to keep the tool away from the model. The server still lists the tool to every client and runs it for anyone who calls it, and a client without Apps support ignores the metadata entirely. That is fine for a read-only refresh. A tool that does something consequential needs its own authorization at execution, whatever its visibility (see `ScopedTools` and the per-tool scope policy in the OAuth guide).

## Knowing the client

`McpApps.clientSupports(ctx.clientCapabilities)` tells you whether the request's client declared `io.modelcontextprotocol/ui` with the `mcp-app` media type. Use it to decide whether a tool's text block should carry everything, or can leave the details to the View. To announce Apps support in `server/discover`, pass `extensions = Some(Map(McpApps.serverExtension))` to `McpServer`. Hosts don't require it: they look for `_meta.ui` on the tools.

## Running the example

```bash
sbt health/run   # stdio server: get_system_health, refresh_health, ui://health/dashboard.html
```

A plain MCP client sees two ordinary tools and one resource. To see the dashboard, connect the server to an Apps-capable host, or use the example host in the ext-apps repository (`examples/basic-host`).
