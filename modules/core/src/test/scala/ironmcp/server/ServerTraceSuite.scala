package ironmcp
package server

import cats.effect.IO
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
