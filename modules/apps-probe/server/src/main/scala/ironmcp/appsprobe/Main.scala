package ironmcp
package appsprobe

import cats.Show
import cats.data.{Kleisli, OptionT}
import cats.effect.{ExitCode, IO, IOApp}
import io.circe.{Decoder, Encoder, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.apps.*
import ironmcp.http.McpHttp
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import org.http4s.{HttpRoutes, Request}
import org.typelevel.ci.CIString

/** The MCP Apps probe: Views for checking what a host does with MCP Apps.
  * `hello` is the smallest View there is (if the host renders it, it renders
  * Views); `apps_probe` is a checklist of what the host supports, in Scala.js
  * on `iron-mcp-apps-view`.
  *
  * {{{
  * appsProbe/run                       # Streamable HTTP on http://127.0.0.1:3012/mcp, CORS on
  * appsProbe/run --port 3013
  * appsProbe/run --public-host x.ts.net   # also accept that Host (a tunnel, for ChatGPT)
  * appsProbe/run stdio                 # stdio, for 2026-07-28 clients and, through LegacyStdio, 2025 ones (Codex)
  * }}}
  *
  * Every request is logged to stderr with whether its client declared
  * `io.modelcontextprotocol/ui`, so a host that renders nothing still shows
  * what it asked for.
  */
object Main extends IOApp:

  val View: UiUri = "ui://apps-probe/probe.html"

  type Note = String :| (MaxLength[200] DescribedAs "Any text: the View shows it back, to prove it got the tool's input")

  final case class ProbeArgs(note: Option[Note]) derives Decoder, JsonSchemaOf
  final case class NoArgs() derives Decoder, JsonSchemaOf

  /** What both tools return: the View decodes it from `structuredContent`. */
  final case class ProbeReply(tool: String, serverTime: String, note: Option[String]) derives Encoder.AsObject, JsonSchemaOf

  object ProbeReply:
    given Show[ProbeReply] = r =>
      if r.tool == "apps_probe" then
        s"The MCP Apps probe ran at ${r.serverTime}. A host that supports MCP Apps shows its View, " +
          "a checklist of what the host supports. A host that shows only this text did not render the View."
      else s"pong at ${r.serverTime}"

  private val readOnly = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(false)))

  private def reply(tool: String, note: Option[String]): IO[ProbeReply] =
    IO.realTimeInstant.map(now => ProbeReply(tool, now.toString, note))

  val probe: McpTool[IO] = McpTool[ProbeArgs](
    name = "apps_probe",
    description = "Opens the MCP Apps probe, a View that checks what this host supports: the handshake, host context, " +
      "tool input and result, calls back to the server, messages, links and display modes. Pass a note to see it arrive.",
    title = Some("MCP Apps probe"),
    annotations = readOnly
  )(args => reply("apps_probe", args.note.map(n => n: String))).withUi(View)

  /** For the View only: its call through the host back to this server. */
  val ping: McpTool[IO] = McpTool[NoArgs](
    name = "apps_probe_ping",
    description = "Answers the probe View's call back to the server with the server's time. Only the View calls it.",
    annotations = readOnly
  )(_ => reply("apps_probe_ping", None)).withUi(View, Visibility.App)

  val HelloView: UiUri = "ui://apps-probe/hello.html"

  /** The smallest View there is: static text, then the host's name once the handshake answers. */
  val helloHtml: String =
    """<!DOCTYPE html>
      |<html><head><meta charset="utf-8"><title>Hello</title></head>
      |<body style="font-family: system-ui, sans-serif; margin: 16px">
      |<h1 style="margin: 0 0 8px; font-size: 20px">Hello from an MCP App 👋</h1>
      |<p id="status">If you can read this, the host rendered the View.</p>
      |<script>
      |  const host = window.parent;
      |  host.postMessage({ jsonrpc: "2.0", id: 1, method: "ui/initialize", params: {
      |    appInfo: { name: "hello", version: "0.1.0" }, appCapabilities: {}, protocolVersion: "2026-01-26" } }, "*");
      |  window.addEventListener("message", (e) => {
      |    const m = e.data;
      |    if (!m || m.jsonrpc !== "2.0") return;
      |    if (m.id === 1 && m.result) {
      |      const h = m.result.hostInfo || {};
      |      document.getElementById("status").textContent = "Connected to " + (h.name || "a host") + " " + (h.version || "") + ".";
      |      host.postMessage({ jsonrpc: "2.0", method: "ui/notifications/initialized", params: {} }, "*");
      |    }
      |  });
      |</script>
      |</body></html>
      |""".stripMargin

  val hello: McpTool[IO] = McpTool[NoArgs](
    name = "hello",
    description = "Shows a hello-world MCP App View. Use it to check whether this host renders MCP Apps.",
    annotations = readOnly
  )(_ => IO.pure("Hello! A host that supports MCP Apps shows a small 'Hello from an MCP App' page with this result.")).withUi(HelloView)

  def server: McpServer[IO] = McpServer[IO](
    info = Implementation(name = "iron-mcp-apps-probe", version = "0.1.0"),
    instructions = Some("Call apps_probe to see what this host supports of MCP Apps."),
    tools = Some(ToolSet.of(hello, probe, ping)),
    resources = Some(UiResources.of[IO](
      UiResource(View, "MCP Apps probe", Page.html,
        description = Some("A checklist of what the host supports of MCP Apps."),
        meta = UiResourceMeta(prefersBorder = Some(true))),
      UiResource(HelloView, "Hello", helloHtml, description = Some("A hello-world View."))
    )),
    extensions = Some(Map(McpApps.serverExtension))
  )

  def run(args: List[String]): IO[ExitCode] =
    args match
      case "stdio" :: Nil =>
        log(s"apps-probe: stdio; View ${Page.html.length} chars") >> LegacyStdio.serve(server, Logged(server)).as(ExitCode.Success)
      case _ =>
        option(args, "--port").map(_.toIntOption) match
          case Some(None) => IO.println("--port takes a number").as(ExitCode(2))
          case port =>
            val publicHost = option(args, "--public-host")
            val config = McpHttp.Config(
              cors = true,
              allowHost = h => McpHttp.Loopback.host(h) || publicHost.exists(p => h.takeWhile(_ != ':').equalsIgnoreCase(p))
            )
            val bound: Port = port.flatten.getOrElse(3012).refineUnsafe
            McpHttp
              .serve[IO](requestLog(McpHttp.routes(Logged(server), config)), bound)
              .use(_ =>
                log(s"apps-probe: http://127.0.0.1:$bound/mcp${publicHost.fold("")(h => s" (and Host $h)")}; View ${Page.html.length} chars") >>
                  IO.never
              )
              .as(ExitCode.Success)

  private def option(args: List[String], name: String): Option[String] =
    args.sliding(2).collectFirst { case `name` :: value :: Nil => value }

  def log(line: String): IO[Unit] = IO.blocking(System.err.println(line))

  /** Each HTTP request in one line, before the transport checks it: a client
    * on an older protocol is refused there, and this still shows what it sent.
    */
  private def requestLog(routes: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { (request: Request[IO]) =>
    def header(name: String) = request.headers.get(CIString(name)).map(_.head.value).getOrElse("-")
    OptionT.liftF(request.body.compile.to(Array)).flatMap { bytes =>
      val body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      routes(request.withBodyStream(fs2.Stream.emits(bytes))).semiflatTap { response =>
        log(
          s"${request.method} ${request.uri.path} -> ${response.status.code} " +
            s"[Origin ${header("Origin")}, Host ${header("Host")}, MCP-Protocol-Version ${header("MCP-Protocol-Version")}] " +
            body.take(400)
        )
      }
    }
  }

/** The server, logging each message's method and whether its client declared
  * MCP Apps support.
  */
final class Logged(server: McpServer[IO]) extends ServerHandler[IO]:
  def handle(message: JsonRpcMessage): IO[Option[JsonRpcMessage]] = note(message) >> server.handle(message)

  def handleRecovering(message: JsonRpcMessage)(using cats.ApplicativeThrow[IO]): IO[Option[JsonRpcMessage]] =
    note(message) >> server.handleRecovering(message)

  private def note(message: JsonRpcMessage): IO[Unit] = message match
    case JsonRpcMessage.Request(_, method, params) =>
      val meta = params.flatMap(_.hcursor.downField("_meta").focus).getOrElse(Json.obj())
      val apps = meta.hcursor
        .get[ClientCapabilities](ReservedMeta.ClientCapabilities)
        .toOption
        .fold("no client capabilities")(c => if McpApps.clientSupports(c) then "client declares MCP Apps" else "client does not declare MCP Apps")
      val client = meta.hcursor.downField(ReservedMeta.ClientInfo).get[String]("name").getOrElse("?")
      val what = params.flatMap(p => p.hcursor.get[String]("name").orElse(p.hcursor.get[String]("uri")).toOption).fold("")(" " + _)
      Main.log(s"  $method$what from $client: $apps")
    case JsonRpcMessage.Notification(method, _) => Main.log(s"  $method (notification)")
    case _                                      => IO.unit

/** The View's page: the template with the linked Scala.js View inlined. */
object Page:
  lazy val html: String =
    (resource("/apps-probe/probe.html"), resource("/apps-probe/view.js")) match
      case (Some(template), Some(script)) => UiHtml.inlineScript(template, script)
      case (template, _) =>
        val missing = List(Option.when(template.isEmpty)("apps-probe/probe.html"), Some("apps-probe/view.js")).flatten.mkString(" and ")
        s"<!DOCTYPE html><html><body><p>This apps-probe was built without its View ($missing).</p></body></html>"

  private def resource(path: String): Option[String] =
    Option(getClass.getResourceAsStream(path)).map { in =>
      try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      finally in.close()
    }
