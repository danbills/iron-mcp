package ironmcp
package appsprobe
package view

import cats.effect.{IO, IOApp}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.protocol.*
import org.scalajs.dom
import scala.concurrent.duration.*

/** The probe's View: a checklist of what the host does. Each row starts
  * waiting and turns to ok, failed or absent as the host answers. Rows below
  * the line are buttons: each asks the host for one thing.
  *
  * Everything the host sends is written with `textContent`, never `innerHTML`.
  */
object ProbeView extends IOApp.Simple:

  /** What the server's tools return (`ProbeReply` on the server). */
  final case class ProbeReply(tool: String, serverTime: String, note: Option[String]) derives Decoder

  enum Status(val mark: String, val css: String):
    case Waiting extends Status("…", "waiting")
    case Ok      extends Status("✓", "ok")
    case Failed  extends Status("✗", "failed")
    case Absent  extends Status("–", "absent")

  /** One row of the checklist, by its element id. */
  final case class Row(id: String):
    def set(status: Status, detail: String): IO[Unit] = IO {
      val row = dom.document.getElementById(id)
      if row != null then
        row.setAttribute("data-status", status.css)
        row.querySelector(".mark").textContent = status.mark
        row.querySelector(".detail").textContent = detail
    }

    def waiting: IO[Boolean] = IO {
      val row = dom.document.getElementById(id)
      row != null && row.getAttribute("data-status") == Status.Waiting.css
    }

  val handshake   = Row("handshake")
  val capabilities = Row("capabilities")
  val context     = Row("context")
  val theme       = Row("theme")
  val toolInput   = Row("tool-input")
  val toolResult  = Row("tool-result")
  val contextLive = Row("context-changed")

  val serverTool  = Row("server-tool")
  val readRes     = Row("read-resource")
  val message     = Row("message")
  val modelCtx    = Row("model-context")
  val link        = Row("open-link")
  val fullscreen  = Row("display-mode")
  val logRow      = Row("log")

  def run: IO[Unit] =
    // The handshake waits for the host; a page opened outside one waits forever, so say so after a while.
    val outsideHost = IO.sleep(5.seconds) >> handshake.waiting.ifM(
      handshake.set(Status.Failed, "no answer to ui/initialize after 5 s: is this page inside an MCP Apps host?"),
      IO.unit
    )
    val views = for
      dispatcher <- Dispatcher.parallel[IO]
      _          <- outsideHost.background
      view       <- McpAppView.inWindow[IO](
        Implementation(name = "iron-mcp-apps-probe", version = "0.1.0"),
        AppCapabilities(availableDisplayModes = Some(List(DisplayMode.`inline`, DisplayMode.fullscreen)))
      )
      _          <- HostTheming.follow(view)
      _          <- AutoResize(view)
    yield (view, dispatcher)
    views.use { (view, dispatcher) =>
      shown(view) >> buttons(view, dispatcher) >>
        (events(view), contextChanges(view)).parTupled.void
    }

  /** What the handshake told us. */
  def shown(view: McpAppView[IO]): IO[Unit] =
    for
      _ <- handshake.set(Status.Ok, s"${view.hostInfo.name} ${view.hostInfo.version}, protocol ${view.protocolVersion}")
      caps = view.hostCapabilities.asJson.asObject.map(_.keys.toList).getOrElse(Nil)
      _ <- capabilities.set(if caps.isEmpty then Status.Absent else Status.Ok, if caps.isEmpty then "none declared" else caps.mkString(", "))
      ctx <- view.hostContext.get
      _ <- context.set(if ctx.raw.isEmpty then Status.Absent else Status.Ok, describe(ctx))
      _ <- theme.set(ctx.theme.fold(Status.Absent)(_ => Status.Ok), ctx.theme.fold("no theme in host context")(t => s"$t, styles: ${ctx.styles.fold(0)(_.variables.size)} CSS variables"))
    yield ()

  def describe(ctx: HostContext): String =
    List(
      ctx.displayMode.map(m => s"display $m"),
      ctx.availableDisplayModes.map(ms => s"modes ${ms.mkString("/")}"),
      ctx.platform.map(p => s"platform $p"),
      ctx.locale.map(l => s"locale $l"),
      ctx.timeZone.map(z => s"tz $z"),
      ctx.containerDimensions.map(d => s"container ${d.asJson.noSpaces}"),
      ctx.safeAreaInsets.map(i => s"safe area ${i.asJson.noSpaces}"),
      ctx.toolInfo.map(t => s"tool ${t.tool.name}")
    ).flatten match
      case Nil    => if ctx.raw.isEmpty then "empty" else s"keys: ${ctx.raw.keys.mkString(", ")}"
      case fields => fields.mkString("; ")

  def events(view: McpAppView[IO]): IO[Unit] =
    view.events.evalMap {
      case HostEvent.ToolInput(arguments) =>
        toolInput.set(Status.Ok, if arguments.isEmpty then "{} (no arguments)" else Json.fromJsonObject(arguments).noSpaces)
      case HostEvent.ToolInputPartial(arguments) =>
        toolInput.set(Status.Waiting, s"streaming: ${Json.fromJsonObject(arguments).noSpaces}")
      case HostEvent.ToolResult(result) =>
        result.structuredAs[ProbeReply] match
          case Right(reply) =>
            toolResult.set(Status.Ok, s"${reply.tool} at ${reply.serverTime}${reply.note.fold("")(n => s", note \"$n\"")}")
          case Left(_) if result.failed => toolResult.set(Status.Failed, s"error result: ${result.textContent}")
          case Left(error) => toolResult.set(Status.Failed, s"no structuredContent the View can read (${error.getMessage}); text: ${result.textContent.take(120)}")
      case HostEvent.ToolCancelled(reason) => toolResult.set(Status.Failed, s"cancelled${reason.fold("")(r => s": $r")}")
      case HostEvent.Unreadable(method, _, reason) => contextLive.set(Status.Failed, s"unreadable $method: $reason")
    }.compile.drain

  def contextChanges(view: McpAppView[IO]): IO[Unit] =
    view.hostContext.discrete.drop(1).zipWithIndex.evalMap { (ctx, n) =>
      contextLive.set(Status.Ok, s"${n + 1} update(s); now ${describe(ctx)}") >>
        theme.set(ctx.theme.fold(Status.Absent)(_ => Status.Ok), ctx.theme.fold("no theme")(t => s"$t (updated)"))
    }.compile.drain

  /** Wires each button to its request. A host error, or no answer in 10 s, fails the row. */
  def buttons(view: McpAppView[IO], dispatcher: Dispatcher[IO]): IO[Unit] =
    def on(row: Row, label: String)(action: IO[String]): IO[Unit] = IO {
      val button = dom.document.querySelector(s"#${row.id} button")
      if button != null then
        button.textContent = label
        button.addEventListener("click", (_: dom.Event) =>
          dispatcher.unsafeRunAndForget(
            row.set(Status.Waiting, "asked the host…") >>
              action.timeout(10.seconds).attempt.flatMap {
                case Right(detail) => row.set(Status.Ok, detail)
                case Left(error)   => row.set(Status.Failed, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
              }
          )
        )
    }
    val text = (s: String) => List(ContentBlock.Text(s))
    List(
      on(serverTool, "Call server tool")(
        view.callServerTool("apps_probe_ping").map { result =>
          result.structuredAs[ProbeReply].fold(_ => s"answered: ${result.textContent.take(120)}", r => s"server time ${r.serverTime}")
        }
      ),
      on(readRes, "Read own resource")(
        view.readServerResource("ui://apps-probe/probe.html").map { result =>
          val sizes = result.contents.map {
            case ResourceContents.Text(_, body, mime, _) => s"${body.length} chars ${mime.fold("")(m => m: String)}"
            case ResourceContents.Blob(_, _, mime, _)    => s"blob ${mime.fold("")(m => m: String)}"
          }
          sizes.mkString(", ")
        }
      ),
      on(message, "Send a chat message")(
        view.sendMessage(text("The MCP Apps probe sent this message.")).map(ok => if ok then "host accepted it" else "host declined it")
      ),
      on(modelCtx, "Update model context")(
        view.updateModelContext(text("The MCP Apps probe View is open."), Some(JsonObject("probe" -> "open".asJson)))
          .as("host accepted it")
      ),
      on(link, "Open a link")(
        view.openLink("https://modelcontextprotocol.io/").map(ok => if ok then "host opened it" else "host declined it")
      ),
      on(fullscreen, "Toggle fullscreen")(
        view.hostContext.get.flatMap { ctx =>
          val want = if ctx.displayMode.contains(DisplayMode.fullscreen) then DisplayMode.`inline` else DisplayMode.fullscreen
          view.requestDisplayMode(want).map(got => s"asked for $want, host gave $got")
        }
      ),
      on(logRow, "Send a log message")(
        view.log(LoggingLevel.info, "apps-probe View says hello".asJson, Some("apps-probe")).as("sent (a notification: no answer expected)")
      )
    ).sequence_
