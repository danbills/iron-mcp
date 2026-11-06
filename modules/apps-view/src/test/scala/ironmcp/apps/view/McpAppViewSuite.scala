package ironmcp.apps.view

import cats.effect.{IO, Ref, Resource}
import io.circe.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** The View side of MCP Apps against a scripted host, over the in-memory port. */
class McpAppViewSuite extends CatsEffectSuite:

  private val appInfo = Implementation(name = "test-view", version = "1.0.0")

  /** `ui/initialize`'s answer, spec-shaped, as a 2025-era host would send it. */
  private val initResult = parse("""{
    "protocolVersion": "2026-01-26",
    "hostInfo": {"name": "test-host", "version": "2.0.0"},
    "hostCapabilities": {"openLinks": {}, "serverTools": {"listChanged": true}, "message": {"text": {}}},
    "hostContext": {
      "theme": "dark",
      "displayMode": "inline",
      "availableDisplayModes": ["inline", "fullscreen"],
      "containerDimensions": {"maxHeight": 600, "width": 400},
      "locale": "en-GB",
      "platform": "desktop",
      "styles": {"variables": {"--color-text-primary": "#eee", "--color-text-secondary": null}, "css": {"fonts": "@import url(x);"}},
      "toolInfo": {"id": 7, "tool": {"name": "get_system_health", "inputSchema": {"type": "object"}}},
      "x-host-extra": 42
    }
  }""").toOption.get

  private final case class Session(host: HostPort.InMemory[IO], view: McpAppView[IO], initialize: Json)

  /** Connects a View, answering its `ui/initialize` as the host. */
  private def session(onTeardown: IO[Unit] = IO.unit): Resource[IO, Session] =
    for
      host <- Resource.eval(HostPort.inMemory[IO])
      init <- Resource.eval(IO.deferred[Json])
      _ <- (for
             request <- host.fromView
             _       <- init.complete(request)
             _       <- host.toView(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> request.hcursor.downField("id").focus.get, "result" -> initResult))
           yield ()).background
      view    <- McpAppView.connect[IO](appInfo, host.port, onTeardown = onTeardown)
      request <- Resource.eval(init.get)
    yield Session(host, view, request)

  private def method(message: Json): Option[String] = message.hcursor.get[String]("method").toOption
  private def notification(name: String, params: Json): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "method" -> name.asJson, "params" -> params)
  private def respond(to: Json, result: Json): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> to.hcursor.downField("id").focus.get, "result" -> result)

  test("the handshake: ui/initialize with the app's info, then initialized"):
    session().use { s =>
      s.host.fromView.map { initialized =>
        assertEquals(method(s.initialize), Some("ui/initialize"))
        val params = s.initialize.hcursor.downField("params")
        assertEquals(params.get[String]("protocolVersion"), Right("2026-01-26"))
        assertEquals(params.downField("appInfo").focus, Some(Json.obj("name" -> "test-view".asJson, "version" -> "1.0.0".asJson)))
        assertEquals(params.get[JsonObject]("appCapabilities"), Right(JsonObject.empty))
        assertEquals(method(initialized), Some("ui/notifications/initialized"))
        assertEquals((s.view.hostInfo.name: String), "test-host")
        assert(s.view.hostCapabilities.openLinks.isDefined)
        assertEquals(s.view.hostCapabilities.serverTools.flatMap(_.listChanged), Some(true))
      }
    }

  test("the host context decodes, keeping unknown keys and skipping undefined variables"):
    session().use { s =>
      s.view.hostContext.get.map { context =>
        assertEquals(context.theme, Some(Theme.dark))
        assertEquals(context.displayMode, Some(DisplayMode.`inline`))
        assertEquals(context.containerDimensions, Some(ContainerDimensions(width = Some(400), maxHeight = Some(600))))
        assertEquals(context.platform, Some(Platform.desktop))
        assertEquals(context.styles.map(_.variables), Some(Map("--color-text-primary" -> "#eee")))
        assertEquals(context.styles.flatMap(_.fonts), Some("@import url(x);"))
        assertEquals(context.toolInfo.map(t => (t.tool.name: String)), Some("get_system_health"))
        assertEquals(context.raw("x-host-extra"), Some(Json.fromInt(42)))
      }
    }

  test("host-context-changed merges key by key; a field it cannot read is absent, not an error"):
    session().use { s =>
      for
        _       <- s.host.fromView
        _       <- s.host.toView(notification("ui/notifications/host-context-changed", Json.obj("theme" -> "light".asJson, "platform" -> "toaster".asJson)))
        context <- s.view.hostContext.discrete.find(_.theme.contains(Theme.light)).compile.lastOrError.timeout(2.seconds)
      yield
        assertEquals(context.locale, Some("en-GB"))
        assertEquals(context.platform, None)
    }

  test("tool input, partial input, result and cancellation arrive as events, in order"):
    session().use { s =>
      val result = Json.obj(
        "content"           -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> "3/4 services healthy.".asJson)),
        "structuredContent" -> Json.obj("healthy" -> 3.asJson)
      )
      for
        _ <- s.host.fromView
        _ <- s.host.toView(notification("ui/notifications/tool-input-partial", Json.obj("arguments" -> Json.obj("city" -> "Os".asJson))))
        _ <- s.host.toView(notification("ui/notifications/tool-input", Json.obj("arguments" -> Json.obj("city" -> "Oslo".asJson))))
        _ <- s.host.toView(notification("ui/notifications/tool-result", result))
        _ <- s.host.toView(notification("ui/notifications/tool-result", Json.obj("content" -> "nope".asJson)))
        _ <- s.host.toView(notification("ui/notifications/tool-cancelled", Json.obj("reason" -> "user".asJson)))
        events <- s.view.events.take(5).compile.toList.timeout(2.seconds)
      yield events match
        case List(
              HostEvent.ToolInputPartial(partial),
              HostEvent.ToolInput(input),
              HostEvent.ToolResult(toolResult),
              HostEvent.Unreadable(unreadable, _, _),
              HostEvent.ToolCancelled(reason)
            ) =>
          assertEquals(partial("city"), Some("Os".asJson))
          assertEquals(input("city"), Some("Oslo".asJson))
          assertEquals(toolResult.textContent, "3/4 services healthy.")
          assertEquals(toolResult.structuredAs[JsonObject].map(_("healthy")), Right(Some(3.asJson)))
          assertEquals(unreadable, "ui/notifications/tool-result")
          assertEquals(reason, Some("user"))
        case other => fail(s"unexpected events: $other")
    }

  test("callServerTool goes through the host as tools/call; an older server's result still decodes"):
    session().use { s =>
      for
        _       <- s.host.fromView
        call    <- s.view.callServerTool("refresh_health").start
        request <- s.host.fromView
        _       <- s.host.toView(respond(request, Json.obj("content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> "Refreshed".asJson)))))
        result  <- call.joinWithNever
      yield
        assertEquals(method(request), Some("tools/call"))
        assertEquals(request.hcursor.downField("params").focus, Some(Json.obj("name" -> "refresh_health".asJson, "arguments" -> Json.obj())))
        assertEquals(result.textContent, "Refreshed")
        assert(!result.failed)
    }

  test("a JSON-RPC error from the host fails the call with McpAppViewError"):
    session().use { s =>
      for
        _       <- s.host.fromView
        call    <- s.view.callServerTool("missing", Json.obj("x" -> 1.asJson).asObject.get).attempt.start
        request <- s.host.fromView
        _ <- s.host.toView(
               Json.obj(
                 "jsonrpc" -> "2.0".asJson,
                 "id"      -> request.hcursor.downField("id").focus.get,
                 "error"   -> Json.obj("code" -> (-32602).asJson, "message" -> "Unknown tool: missing".asJson)
               )
             )
        result <- call.joinWithNever
      yield result match
        case Left(McpAppViewError(failed, error)) =>
          assertEquals(failed, "tools/call")
          assertEquals(error.code, ErrorCode.InvalidParams)
        case other => fail(s"expected an McpAppViewError, got $other")
    }

  test("readServerResource fills the fields 2026-07-28 adds"):
    session().use { s =>
      for
        _       <- s.host.fromView
        read    <- s.view.readServerResource("ui://health/dashboard.html").start
        request <- s.host.fromView
        _ <- s.host.toView(
               respond(request, Json.obj("contents" -> Json.arr(Json.obj("uri" -> "ui://health/dashboard.html".asJson, "text" -> "<html>".asJson))))
             )
        result <- read.joinWithNever
      yield
        assertEquals(method(request), Some("resources/read"))
        assertEquals(result.contents.size, 1)
    }

  test("ui/resource-teardown runs the handler before it is answered; unknown requests get -32601; ping is answered"):
    Ref.of[IO, Boolean](false).flatMap { tornDown =>
      session(onTeardown = tornDown.set(true)).use { s =>
        for
          _        <- s.host.fromView
          _        <- s.host.toView(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> "t1".asJson, "method" -> "ui/resource-teardown".asJson, "params" -> Json.obj()))
          teardown <- s.host.fromView
          wasDown  <- tornDown.get
          _        <- s.host.toView(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 9.asJson, "method" -> "tools/list".asJson))
          unknown  <- s.host.fromView
          _        <- s.host.toView(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 10.asJson, "method" -> "ping".asJson))
          ping     <- s.host.fromView
        yield
          assertEquals(teardown, Json.obj("jsonrpc" -> "2.0".asJson, "id" -> "t1".asJson, "result" -> Json.obj()))
          assert(wasDown)
          assertEquals(unknown.hcursor.downField("error").get[Int]("code"), Right(-32601))
          assertEquals(ping.hcursor.get[JsonObject]("result"), Right(JsonObject.empty))
      }
    }

  test("View requests and notifications leave out absent fields"):
    session().use { s =>
      for
        _       <- s.host.fromView
        _       <- s.view.sendSizeChanged(None, Some(480))
        size    <- s.host.fromView
        _       <- s.view.log(LoggingLevel.info, "hello".asJson)
        log     <- s.host.fromView
        message <- s.view.sendMessage(List(ContentBlock.Text("Show me more"))).start
        request <- s.host.fromView
        _       <- s.host.toView(respond(request, Json.obj("isError" -> true.asJson)))
        sent    <- message.joinWithNever
        mode    <- s.view.requestDisplayMode(DisplayMode.fullscreen).start
        modeReq <- s.host.fromView
        _       <- s.host.toView(respond(modeReq, Json.obj("mode" -> "fullscreen".asJson)))
        chosen  <- mode.joinWithNever
      yield
        assertEquals(size.hcursor.downField("params").focus, Some(Json.obj("height" -> 480.asJson)))
        assertEquals(log.hcursor.downField("params").focus, Some(Json.obj("level" -> "info".asJson, "data" -> "hello".asJson)))
        assertEquals(
          request.hcursor.downField("params").focus,
          Some(Json.obj("role" -> "user".asJson, "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> "Show me more".asJson))))
        )
        assert(!sent, "the host refused the message")
        assertEquals(chosen, DisplayMode.fullscreen)
    }

  test("structuredAs rejects data that does not decode"):
    final case class Health(healthy: Int) derives Decoder
    val result = CallToolResult.structured("x", Json.obj("healthy" -> "lots".asJson))
    assert(result.structuredAs[Health].isLeft)
    assert(CallToolResult.text("x").structuredAs[Health].isLeft)
    assertEquals(CallToolResult.structured("x", Json.obj("healthy" -> 3.asJson)).structuredAs[Health], Right(Health(3)))
