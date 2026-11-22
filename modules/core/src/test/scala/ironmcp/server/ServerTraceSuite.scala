package ironmcp
package server

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import munit.CatsEffectSuite

class ServerTraceSuite extends CatsEffectSuite:

  final case class EmptyArgs() derives Decoder, JsonSchemaOf

  private val demoServer = McpServer[IO](
    info = Implementation("trace-demo", "1.0.0"),
    tools = Some(ToolSet.of(
      McpTool[EmptyArgs]("ping", "Ping")(_ => IO.pure("pong"))
    ))
  )

  private val reqMeta = RequestMeta(LatestProtocolVersion, ClientCapabilities(), None)

  test("TraceRecorder wraps handler, attaches _meta.tracing, and renders Json/Perfetto/OtlpJson"):
    for
      rec <- TraceRecorder.inMemory[IO]("trace-demo", "1.0.0")
      srv  = rec.wrap(demoServer)
      tp   = "00-0123456789abcdef0123456789abcdef-1111222233334444-01"
      req  = JsonRpcMessage.Request(
               RequestId.Num(1L),
               "tools/call",
               Some(Json.obj(
                 "name" -> Json.fromString("ping"),
                 "_meta" -> Json.obj(
                   "traceparent" -> Json.fromString(tp),
                   "io.modelcontextprotocol/protocolVersion" -> Json.fromString(LatestProtocolVersion),
                   "io.modelcontextprotocol/clientCapabilities" -> Json.obj()
                 )
               ))
             )
      resp <- srv.handle(req)
      json <- rec.render(TraceFormat.Json).map(parse(_).toOption.get)
      perf <- rec.render(TraceFormat.Perfetto).map(parse(_).toOption.get)
      otlp <- rec.render(TraceFormat.OtlpJson).map(parse(_).toOption.get)
    yield
      val res = resp.collect { case JsonRpcMessage.Success(_, r) => r }.get
      assertEquals(res.hcursor.downField("_meta").downField("tracing").get[String]("traceId"), Right("0123456789abcdef0123456789abcdef"))
      assertEquals(json.hcursor.get[Int]("spanCount"), Right(1))
      assertEquals(perf.hcursor.downField("traceEvents").downN(2).get[String]("ph"), Right("X"))
      assertEquals(perf.hcursor.downField("traceEvents").downN(2).get[String]("name"), Right("tools/call ping"))
      assertEquals(otlp.hcursor.downField("resourceSpans").downN(0).downField("scopeSpans").downN(0).downField("spans").downN(0).get[String]("parentSpanId"), Right("1111222233334444"))

  test("TraceRecorder.resources serves trace://server/perfetto.json and otlp.json"):
    for
      rec  <- TraceRecorder.inMemory[IO]("trace-demo", "1.0.0")
      list <- rec.resources.list(ListResourcesParams(reqMeta))
      read <- rec.resources.read(ReadResourceParams("trace://server/perfetto.json", reqMeta))
    yield
      assertEquals(list.resources.map(_.uri: String), List("trace://server/perfetto.json", "trace://server/otlp.json", "trace://server/trace.json"))
      val text = read.asInstanceOf[ReadResourceResult].contents.head.asInstanceOf[ResourceContents.Text].text
      assert(parse(text).isRight)

  /** A handler that answers every request with `reply`, or fails with it. */
  private def answering(reply: IO[Option[JsonRpcMessage]]): ServerHandler[IO] = new ServerHandler[IO]:
    def handle(message: JsonRpcMessage): IO[Option[JsonRpcMessage]] = reply
    def handleRecovering(message: JsonRpcMessage)(using cats.ApplicativeThrow[IO]): IO[Option[JsonRpcMessage]] = reply

  private def request(method: NonEmptyString, params: Json = Json.obj()) =
    JsonRpcMessage.Request(RequestId.Num(1L), method, Some(params))

  private val sink: NotificationSink[IO] = _ => IO.unit

  private def span(attributes: Map[String, Json], status: TraceStatus = TraceStatus.Ok) =
    TraceSpan("a" * 32, "b" * 16, None, "op", "op", None, 1_000L, 2_000L, status, attributes)

  test("spans are named by target, statuses come from the reply, and every entry point records"):
    for
      rec <- TraceRecorder.inMemory[IO]("t", "1")
      ok   = rec.wrap(answering(IO.pure(Some(JsonRpcMessage.Success(RequestId.Num(1L), Json.obj("isError" -> Json.True))))))
      _   <- ok.handleRecovering(request("resources/read", Json.obj("uri" -> Json.fromString("file:///x"))))
      _   <- ok.handleEmitting(request("prompts/get", Json.obj("name" -> Json.fromString("greet"))), sink)
      _   <- ok.handleEmittingRecovering(request("ping"), sink)
      bad  = rec.wrap(answering(IO.pure(Some(JsonRpcMessage.Failure(Some(RequestId.Num(1L)), JsonRpcError(ErrorCode.MethodNotFound, "nope", None))))))
      _   <- bad.handle(request("nothing/here"))
      quiet = rec.wrap(answering(IO.pure(None)))
      _   <- quiet.handle(request("tools/list"))
      boom  = rec.wrap(answering(IO.raiseError(RuntimeException("boom"))))
      err <- boom.handle(request("tools/call", Json.obj("name" -> Json.fromString("x")))).attempt
      note <- ok.handle(JsonRpcMessage.Notification("notifications/initialized", None))
      snap <- rec.snapshot
    yield
      assertEquals(snap.spans.map(_.name), List("resources/read file:///x", "prompts/get greet", "ping", "nothing/here", "tools/list", "tools/call x"))
      assertEquals(snap.spans.map(_.status), List(
        TraceStatus.Error(-32000, "tool returned isError=true"),
        TraceStatus.Error(-32000, "tool returned isError=true"),
        TraceStatus.Error(-32000, "tool returned isError=true"),
        TraceStatus.Error(ErrorCode.MethodNotFound.code, "nope"),
        TraceStatus.Ok,
        TraceStatus.Error(-32603, "boom")
      ))
      assert(err.left.exists(_.getMessage == "boom"), err)
      assert(note.isDefined, "a notification passes through untraced")
      assertEquals(snap.spans.head.attributes.get("mcp.is_error"), Some(Json.True))
      assert(snap.spans.forall(s => s.traceId.length == 32 && s.parentSpanId.isEmpty), snap.spans)

  test("_meta.tracing: added to an object result, kept beside other _meta, and left off when asked or not an object"):
    for
      rec    <- TraceRecorder.inMemory[IO]("t", "1")
      withMeta = Json.obj("_meta" -> Json.obj("k" -> Json.fromString("v")))
      a      <- rec.wrap(answering(IO.pure(Some(JsonRpcMessage.Success(RequestId.Num(1L), withMeta))))).handle(request("ping"))
      b      <- rec.wrap(answering(IO.pure(Some(JsonRpcMessage.Success(RequestId.Num(1L), Json.fromInt(3)))))).handle(request("ping"))
      c      <- rec.wrap(answering(IO.pure(Some(JsonRpcMessage.Success(RequestId.Num(1L), Json.obj())))), attachMeta = false).handle(request("ping"))
      d      <- rec.wrap(answering(IO.pure(Some(JsonRpcMessage.Failure(Some(RequestId.Num(1L)), JsonRpcError(ErrorCode.InternalError, "x", None)))))).handle(request("ping"))
    yield
      val meta = a.collect { case JsonRpcMessage.Success(_, r) => r.hcursor.downField("_meta") }.get
      assertEquals(meta.get[String]("k"), Right("v"))
      assertEquals(meta.downField("tracing").get[String]("server"), Right("t"))
      assertEquals(b, Some(JsonRpcMessage.Success(RequestId.Num(1L), Json.fromInt(3))))
      assertEquals(c, Some(JsonRpcMessage.Success(RequestId.Num(1L), Json.obj())))
      assert(d.exists(_.isInstanceOf[JsonRpcMessage.Failure]), d)

  test("a malformed traceparent starts a new trace"):
    for
      rec  <- TraceRecorder.inMemory[IO]("t", "1")
      _    <- rec.wrap(answering(IO.pure(None))).handle(request("ping", Json.obj("_meta" -> Json.obj("traceparent" -> Json.fromString("00-short-1-01")))))
      snap <- rec.snapshot
    yield
      assertEquals(snap.spans.map(_.parentSpanId), List(None))
      assertNotEquals(snap.spans.head.traceId, "short")

  test("the recorder keeps the latest maxSpans"):
    for
      rec  <- TraceRecorder.inMemory[IO]("t", "1", maxSpans = 2)
      _    <- List("a", "b", "c").traverse_(n => rec.record(span(Map.empty).copy(name = n)))
      snap <- rec.snapshot
    yield assertEquals(snap.spans.map(_.name), List("b", "c"))

  test("renderings carry errors and every kind of attribute"):
    val trace = ServerTrace("t", "1", List(span(
      Map(
        "s" -> Json.fromString("x"),
        "b" -> Json.True,
        "i" -> Json.fromLong(7L),
        "d" -> Json.fromDoubleOrNull(1.5),
        "o" -> Json.obj("k" -> Json.fromInt(1))
      ),
      TraceStatus.Error(-1, "bad")
    ).copy(parentSpanId = Some("c" * 16))))
    val json = TraceRenderer.renderJson(trace, TraceFormat.Json)
    val perf = TraceRenderer.renderJson(trace, TraceFormat.Perfetto)
    val otlp = TraceRenderer.renderJson(trace, TraceFormat.OtlpJson)
    val attrs = otlp.hcursor.downField("resourceSpans").downN(0).downField("scopeSpans").downN(0).downField("spans").downN(0)
    val values = attrs.downField("attributes").focus.flatMap(_.asArray).get.map(a => a.hcursor.get[String]("key").toOption.get -> a.hcursor.downField("value").focus.get).toMap
    assertEquals(json.hcursor.downField("spans").downN(0).downField("status").get[String]("message"), Right("bad"))
    assertEquals(perf.hcursor.downField("traceEvents").downN(2).downField("args").get[String]("status"), Right("error(-1): bad"))
    assertEquals(perf.hcursor.downField("traceEvents").downN(2).downField("args").get[String]("parent_span_id"), Right("c" * 16))
    assertEquals(attrs.downField("status").get[Int]("code"), Right(2))
    assertEquals(values("s"), Json.obj("stringValue" -> Json.fromString("x")))
    assertEquals(values("b"), Json.obj("boolValue" -> Json.True))
    assertEquals(values("i"), Json.obj("intValue" -> Json.fromString("7")))
    assertEquals(values("d"), Json.obj("doubleValue" -> Json.fromDoubleOrNull(1.5)))
    assertEquals(values("o"), Json.obj("stringValue" -> Json.fromString("""{"k":1}""")))
    assertEquals(TraceFormat.Perfetto.mimeType: String, "application/json")

  test("TraceFormat.parse reads each format's names and refuses others"):
    assertEquals(List("json", " JSON ", "perfetto", "chrome", "chrome_trace", "otlp", "otlp_json", "otlp-json").map(TraceFormat.parse),
      List(Right(TraceFormat.Json), Right(TraceFormat.Json), Right(TraceFormat.Perfetto), Right(TraceFormat.Perfetto), Right(TraceFormat.Perfetto),
        Right(TraceFormat.OtlpJson), Right(TraceFormat.OtlpJson), Right(TraceFormat.OtlpJson)))
    assert(TraceFormat.parse("xml").left.exists(_.contains("Unknown trace format 'xml'")))

  test("reading a trace resource that does not exist fails"):
    TraceRecorder.inMemory[IO]("t", "1").flatMap(_.resources.read(ReadResourceParams("trace://server/none.json", reqMeta)).attempt).map { r =>
      assert(r.isLeft, r)
    }

  test("a failure with no message is named by its class"):
    for
      rec  <- TraceRecorder.inMemory[IO]("t", "1")
      _    <- rec.wrap(answering(IO.raiseError(IllegalStateException()))).handle(request("ping")).attempt
      snap <- rec.snapshot
    yield assertEquals(snap.spans.map(_.status), List(TraceStatus.Error(-32603, "IllegalStateException")))

  test("ids are lower-case hex of the W3C lengths"):
    val hex = "^[0-9a-f]+$".r
    assert(List.fill(50)(TraceRecorder.randomHex16()).forall(id => id.length == 16 && hex.matches(id)))
    assert(List.fill(50)(TraceRecorder.randomHex32()).forall(id => id.length == 32 && hex.matches(id)))
