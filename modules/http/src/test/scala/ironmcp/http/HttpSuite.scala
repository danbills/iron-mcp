package ironmcp
package http

import cats.effect.IO
import io.circe.{Decoder, Json, parser}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.circe.given
import ironmcp.protocol.{Implementation}
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

class HttpSuite extends CatsEffectSuite:

  final case class EchoArgs(message: String :| Not[Empty]) derives Decoder, JsonSchemaOf

  private val echo = McpTool[EchoArgs]("echo", "Echoes.")(args => IO.pure(s"Echo: ${args.message: String}"))

  private val app = McpHttp
    .routes(McpServer[IO](info = Implementation(name = "http", version = "0.1.0"), tools = Some(ToolSet.of(echo))))
    .orNotFound

  private val meta = """{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

  private def call(name: String) =
    s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":{"message":"hi"},"_meta":$meta}}"""

  private val standard = List(
    "MCP-Protocol-Version" -> "2026-07-28",
    "Mcp-Method"           -> "tools/call",
    "Mcp-Name"             -> "echo",
    "Host"                 -> "localhost:5000"
  )

  private def post(body: String, headers: List[(String, String)] = standard, path: String = "/mcp"): IO[(Status, Json)] =
    val request = Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withEntity(body)
      .putHeaders(headers.map((k, v) => Header.Raw(CIString(k), v))*)
    app.run(request).flatMap { response =>
      response.as[String].map(text => (response.status, parser.parse(text).getOrElse(Json.Null)))
    }

  private def code(json: Json) = json.hcursor.downField("error").get[Int]("code").toOption

  test("a tools/call with matching headers is answered as JSON"):
    post(call("echo")).map { (status, json) =>
      assertEquals(status, Status.Ok)
      assertEquals(json.hcursor.downField("result").downField("content").downArray.get[String]("text"), Right("Echo: hi"))
    }

  test("a missing MCP-Protocol-Version header is a header mismatch"):
    post(call("echo"), standard.filterNot(_._1 == "MCP-Protocol-Version")).map { (status, json) =>
      assertEquals(status, Status.BadRequest)
      assertEquals(code(json), Some(-32020))
    }

  test("an unsupported version lists what is supported"):
    post(call("echo"), standard.map((k, v) => if k == "MCP-Protocol-Version" then k -> "2025-11-25" else k -> v)).map { (status, json) =>
      assertEquals(status, Status.BadRequest)
      assertEquals(code(json), Some(-32022))
      assertEquals(json.hcursor.downField("error").downField("data").get[List[String]]("supported"), Right(List("2026-07-28")))
    }

  test("an Mcp-Name that disagrees with the body is refused"):
    post(call("echo"), standard.map((k, v) => if k == "Mcp-Name" then k -> "delete_everything" else k -> v)).map { (status, json) =>
      assertEquals(status, Status.BadRequest)
      assertEquals(code(json), Some(-32020))
    }

  test("Mcp-Name may be base64-encoded"):
    post(call("echo"), standard.map((k, v) => if k == "Mcp-Name" then k -> "=?base64?ZWNobw==?=" else k -> v)).map { (status, _) =>
      assertEquals(status, Status.Ok)
    }

  test("an unknown method is a 404 with -32601"):
    val body = s"""{"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"_meta":$meta}}"""
    post(body, standard.map((k, v) => if k == "Mcp-Method" then k -> "tasks/get" else k -> v)).map { (status, json) =>
      assertEquals(status, Status.NotFound)
      assertEquals(code(json), Some(-32601))
    }

  test("a foreign Origin is forbidden"):
    post(call("echo"), ("Origin" -> "https://evil.example") :: standard).map((status, _) => assertEquals(status, Status.Forbidden))

  test("a loopback Origin is allowed"):
    post(call("echo"), ("Origin" -> "http://localhost:6274") :: standard).map((status, _) => assertEquals(status, Status.Ok))

  test("a non-loopback Host is forbidden (DNS rebinding)"):
    post(call("echo"), standard.map((k, v) => if k == "Host" then k -> "attacker.example" else k -> v))
      .map((status, _) => assertEquals(status, Status.Forbidden))

  test("a notification is accepted with 202"):
    post("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{}}""").map((status, _) => assertEquals(status, Status.Accepted))

  test("an oversized body is refused before parsing"):
    val small = McpHttp
      .routes(McpServer[IO](info = Implementation(name = "http", version = "0.1.0")), McpHttp.Config(maxBodyBytes = 16))
      .orNotFound
    small.run(Request[IO](Method.POST, uri"/mcp").withEntity(call("echo"))).map(r => assertEquals(r.status, Status.PayloadTooLarge))

  test("GET and DELETE are 405: this revision has no stream or session to open"):
    for
      get    <- app.run(Request[IO](Method.GET, uri"/mcp"))
      delete <- app.run(Request[IO](Method.DELETE, uri"/mcp"))
    yield
      assertEquals(get.status, Status.MethodNotAllowed)
      assertEquals(delete.status, Status.MethodNotAllowed)

  test("other paths are not the endpoint"):
    post(call("echo"), path = "/other").map((status, _) => assertEquals(status, Status.NotFound))

class HttpProgressSuite extends CatsEffectSuite:
  import cats.syntax.all.*
  import io.circe.syntax.*
  import io.github.iltotore.iron.autoRefine

  final case class Steps(steps: Int :| Interval.Closed[1, 10]) derives Decoder, JsonSchemaOf

  private val countdown = McpTool.reporting[IO, Steps]("countdown", "Counts down.") { (args, emit) =>
    val total: Int = args.steps
    (1 to total).toList.traverse_(i => emit.progress(i.toDouble, Some(total.toDouble))).as(s"Done after $total steps.")
  }

  private val app = McpHttp.routes(McpServer[IO](Implementation("http-progress", "0"), tools = Some(ToolSet.of(countdown)))).orNotFound

  private def body(withToken: Boolean) =
    val token = if withToken then ""","progressToken":"p1"""" else ""
    s"""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"countdown","arguments":{"steps":2},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}$token}}}"""

  private def post(withToken: Boolean, accept: String): IO[Response[IO]] =
    app.run(
      Request[IO](Method.POST, Uri.unsafeFromString("/mcp"))
        .withEntity(body(withToken))
        .putHeaders(
          Header.Raw(CIString("MCP-Protocol-Version"), "2026-07-28"),
          Header.Raw(CIString("Mcp-Method"), "tools/call"),
          Header.Raw(CIString("Mcp-Name"), "countdown"),
          Header.Raw(CIString("Accept"), accept),
          Header.Raw(CIString("Host"), "localhost:5000")
        )
    )

  private def isStream(r: Response[IO]) = r.contentType.exists(_.mediaType.satisfies(MediaType.`text/event-stream`))

  test("progress turns the reply into an SSE stream: the notifications, then the response"):
    post(withToken = true, accept = "application/json, text/event-stream").flatMap { response =>
      assertEquals(response.status, Status.Ok)
      assert(isStream(response), response.contentType.toString)
      response.body.through(ServerSentEvent.decoder[IO]).compile.toList.map { events =>
        val messages = events.flatMap(_.data).map(parser.parse(_).toOption.get)
        assertEquals(messages.map(_.hcursor.get[String]("method").toOption), List(Some("notifications/progress"), Some("notifications/progress"), None))
        assertEquals(messages.last.hcursor.get[Int]("id"), Right(4))
        assertEquals(messages.last.hcursor.downField("result").downField("content").downArray.get[String]("text"), Right("Done after 2 steps."))
      }
    }

  test("no progressToken, nothing emitted: plain JSON even when SSE is accepted"):
    post(withToken = false, accept = "application/json, text/event-stream").flatMap { response =>
      assert(!isStream(response))
      response.as[String].map(text => assertEquals(parser.parse(text).toOption.get.hcursor.get[Int]("id"), Right(4)))
    }

  test("a client that does not accept SSE gets plain JSON, without the notifications"):
    post(withToken = true, accept = "application/json").flatMap { response =>
      assert(!isStream(response))
      response.as[String].map(text => assert(text.contains("Done after 2 steps."), text))
    }

  test("McpHttpClient hands the streamed progress to onNotification"):
    import ironmcp.client.McpClient
    for
      seen   <- cats.effect.Ref.of[IO, Int](0)
      c      <- McpClient[IO](McpHttpClient.transport(org.http4s.client.Client.fromHttpApp(app), Uri.unsafeFromString("http://localhost:5000/mcp"), IO.pure(Headers.empty), _ => seen.update(_ + 1)))
      result <- c.callTool("countdown", io.circe.JsonObject("steps" -> 3.asJson), progressToken = Some(ironmcp.protocol.ProgressToken.Num(9)))
      count  <- seen.get
    yield
      assert(result.isInstanceOf[ironmcp.protocol.CallToolResult])
      assertEquals(count, 3)
