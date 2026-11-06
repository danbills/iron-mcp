package ironmcp
package otel

import cats.effect.IO
import io.circe.{Decoder, Json, parser}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import io.opentelemetry.api.common.AttributeKey as JAttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.{InMemoryMetricReader, InMemorySpanExporter}
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import ironmcp.http.McpHeaders
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import org.typelevel.otel4s.oteljava.OtelJava

import scala.jdk.CollectionConverters.*

class OtelHttpSuite extends CatsEffectSuite:

  final case class EchoArgs(message: String :| Not[Empty]) derives Decoder, JsonSchemaOf

  private val echo = McpTool[EchoArgs]("echo", "Echoes.")(args => IO.pure(s"Echo: ${args.message: String}"))

  private val failTool = McpTool[EchoArgs]("fail", "Fails.")(_ => IO.raiseError[String](new RuntimeException("boom")))

  private def setup(): IO[(HttpApp[IO], InMemorySpanExporter, InMemoryMetricReader)] =
    for
      spanExporter <- IO(InMemorySpanExporter.create())
      metricReader <- IO(InMemoryMetricReader.create())
      tracerProvider <- IO(
        SdkTracerProvider
          .builder()
          .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
          .build()
      )
      meterProvider <- IO(
        SdkMeterProvider
          .builder()
          .registerMetricReader(metricReader)
          .build()
      )
      jOtel <- IO(
        OpenTelemetrySdk
          .builder()
          .setTracerProvider(tracerProvider)
          .setMeterProvider(meterProvider)
          .build()
      )
      otelJava  <- OtelJava.fromJOpenTelemetry[IO](jOtel)
      telemetry <- McpTelemetry.fromOtelJava[IO](otelJava)
      server = McpServer[IO](
        info = Implementation(name = "otel-http", version = "0.1.0"),
        tools = Some(ToolSet.of(echo, failTool))
      )
      routes = OtelHttp.routes(server, telemetry)
    yield (routes.orNotFound, spanExporter, metricReader)

  private val standardHeaders = List(
    "MCP-Protocol-Version" -> LatestProtocolVersion,
    "Mcp-Method"           -> "tools/call",
    "Mcp-Name"             -> McpHeaders.encode("echo"),
    "Host"                 -> "localhost:5000"
  )

  private def callBody(name: String, meta: Option[Json] = None) =
    val metaJson = meta.getOrElse(
      Json.obj(
        ReservedMeta.ProtocolVersion    -> Json.fromString(LatestProtocolVersion),
        ReservedMeta.ClientCapabilities -> Json.obj()
      )
    )
    s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":{"message":"hi"},"_meta":$metaJson}}"""

  private def post(
      app: HttpApp[IO],
      body: String,
      headers: List[(String, String)] = standardHeaders
  ): IO[(Status, Json)] =
    val request = Request[IO](org.http4s.Method.POST, uri"/mcp")
      .withEntity(body)
      .putHeaders(headers.map((k, v) => Header.Raw(CIString(k), v))*)
    app.run(request).flatMap { response =>
      response.as[String].map(text => (response.status, parser.parse(text).getOrElse(Json.Null)))
    }

  test("HTTP POST executes tool with server span and correct attributes"):
    setup().flatMap { (app, spanExporter, metricReader) =>
      post(app, callBody("echo")).flatMap { (status, json) =>
        assertEquals(status, Status.Ok)
        assertEquals(
          json.hcursor.downField("result").downField("content").downArray.get[String]("text"),
          Right("Echo: hi")
        )
        val spans = spanExporter.getFinishedSpanItems.asScala.toList
        assertEquals(spans.size, 1)
        val span = spans.head
        assertEquals(span.getName, "tools/call echo")
        assertEquals(span.getKind.name(), "SERVER")
        assertEquals(span.getAttributes.get(JAttributeKey.stringKey("network.transport")), "http")
        assertEquals(span.getAttributes.get(JAttributeKey.stringKey("mcp.error_rung")), "ok")

        val metrics = metricReader.collectAllMetrics().asScala.toList
        val opDur = metrics.find(_.getName == McpSemantics.Metrics.ServerOperationDuration)
        assert(opDur.isDefined)
        IO.unit
      }
    }

  test("HTTP traceparent header only: span adopts header trace ID as parent"):
    setup().flatMap { (app, spanExporter, _) =>
      val traceId = "11111111111111111111111111111111"
      val spanId  = "aaaaaaaaaaaaaaaa"
      val header  = s"00-$traceId-$spanId-01"
      val headers = standardHeaders :+ ("traceparent" -> header)
      post(app, callBody("echo"), headers).map { (status, _) =>
        assertEquals(status, Status.Ok)
        val spans = spanExporter.getFinishedSpanItems.asScala.toList
        assertEquals(spans.size, 1)
        val span = spans.head
        assertEquals(span.getTraceId, traceId)
        assertEquals(span.getParentSpanId, spanId)
        assert(span.getLinks.isEmpty)
      }
    }

  test("params._meta traceparent only: span adopts meta trace ID as parent"):
    setup().flatMap { (app, spanExporter, _) =>
      val traceId = "22222222222222222222222222222222"
      val spanId  = "bbbbbbbbbbbbbbbb"
      val metaJson = Json.obj(
        ReservedMeta.ProtocolVersion    -> Json.fromString(LatestProtocolVersion),
        ReservedMeta.ClientCapabilities -> Json.obj(),
        TraceContext.TraceParentField   -> Json.fromString(s"00-$traceId-$spanId-01")
      )
      post(app, callBody("echo", Some(metaJson))).map { (status, _) =>
        assertEquals(status, Status.Ok)
        val spans = spanExporter.getFinishedSpanItems.asScala.toList
        assertEquals(spans.size, 1)
        val span = spans.head
        assertEquals(span.getTraceId, traceId)
        assertEquals(span.getParentSpanId, spanId)
        assert(span.getLinks.isEmpty)
      }
    }

  test("Section 10.2.3: both header and _meta differ -> meta is parent and header trace is attached as link"):
    setup().flatMap { (app, spanExporter, _) =>
      val httpTraceId = "11111111111111111111111111111111"
      val httpSpanId  = "aaaaaaaaaaaaaaaa"
      val metaTraceId = "22222222222222222222222222222222"
      val metaSpanId  = "bbbbbbbbbbbbbbbb"

      val headers = standardHeaders :+ ("traceparent" -> s"00-$httpTraceId-$httpSpanId-01")
      val metaJson = Json.obj(
        ReservedMeta.ProtocolVersion    -> Json.fromString(LatestProtocolVersion),
        ReservedMeta.ClientCapabilities -> Json.obj(),
        TraceContext.TraceParentField   -> Json.fromString(s"00-$metaTraceId-$metaSpanId-01")
      )

      post(app, callBody("echo", Some(metaJson)), headers).map { (status, _) =>
        assertEquals(status, Status.Ok)
        val spans = spanExporter.getFinishedSpanItems.asScala.toList
        assertEquals(spans.size, 1)
        val span = spans.head
        // Meta wins as the parent trace
        assertEquals(span.getTraceId, metaTraceId)
        assertEquals(span.getParentSpanId, metaSpanId)
        // HTTP header trace is preserved as a link
        assertEquals(span.getLinks.size(), 1)
        val link = span.getLinks.get(0)
        assertEquals(link.getSpanContext.getTraceId, httpTraceId)
        assertEquals(link.getSpanContext.getSpanId, httpSpanId)
      }
    }

  test("tool error via HTTP produces isError result with tool_error rung"):
    setup().flatMap { (app, spanExporter, _) =>
      val headers = standardHeaders.map {
        case ("Mcp-Name", _) => ("Mcp-Name", McpHeaders.encode("fail"))
        case other          => other
      }
      post(app, callBody("fail"), headers).map { (status, json) =>
        assertEquals(status, Status.Ok)
        assertEquals(json.hcursor.downField("result").get[Boolean]("isError"), Right(true))
        val spans = spanExporter.getFinishedSpanItems.asScala.toList
        assertEquals(spans.size, 1)
        val span = spans.head
        assertEquals(span.getAttributes.get(JAttributeKey.stringKey("mcp.error_rung")), "tool_error")
      }
    }
