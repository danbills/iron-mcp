package ironmcp
package otel

import cats.effect.IO
import io.circe.{Decoder, Json, JsonObject}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.opentelemetry.api.common.AttributeKey as JAttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.{InMemoryMetricReader, InMemorySpanExporter}
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.OtelJava

import scala.jdk.CollectionConverters.*

class OtelServerSuite extends CatsEffectSuite:

  final case class ForecastArgs(city: String, days: Option[Int] = None) derives Decoder, JsonSchemaOf
  final case class SearchArgs(query: String) derives Decoder, JsonSchemaOf
  final case class EmptyArgs() derives Decoder, JsonSchemaOf

  private val getForecast = McpTool[ForecastArgs]("get_forecast", "Gets forecast.") { args =>
    IO.pure(s"Forecast for ${args.city}: sunny")
  }

  private val searchIncidents = McpTool[SearchArgs]("search_incidents", "Searches incidents.") { args =>
    if args.query == "zzzz" then IO.pure("(no matches)")
    else IO.pure("Incident 2026-03-14: burst signature")
  }

  private val checkDependency = McpTool[EmptyArgs]("check_dependency", "Checks upstream.") { _ =>
    IO.raiseError[String](new RuntimeException("Upstream archive service returned 503."))
  }

  private def setup(): IO[(OtelServer[IO], InMemorySpanExporter, InMemoryMetricReader)] =
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
        info = Implementation(name = "otel-test", version = "1.0.0"),
        tools = Some(ToolSet.of(getForecast, searchIncidents, checkDependency))
      )
      otelServer <- OtelServer.instrument(server, telemetry)
    yield (otelServer, spanExporter, metricReader)

  private val meta = Json.obj(
    ReservedMeta.ProtocolVersion -> Json.fromString(LatestProtocolVersion),
    ReservedMeta.ClientCapabilities -> Json.obj()
  )

  private def callToolMsg(name: String, args: JsonObject): JsonRpcMessage.Request =
    new JsonRpcMessage.Request(
      RequestId.Num(1L),
      "tools/call",
      Some(
        Json.obj(
          "name"      -> Json.fromString(name),
          "arguments" -> Json.fromJsonObject(args),
          "_meta"     -> meta
        )
      )
    )

  test("tools/call get_forecast generates server span and ok error_rung"):
    setup().flatMap { (server, spanExporter, metricReader) =>
      val msg = callToolMsg("get_forecast", JsonObject("city" -> Json.fromString("Oslo")))
      for
        res <- server.handleRecovering(msg)
        _ = assert(res.isDefined)
        spans = spanExporter.getFinishedSpanItems.asScala.toList
        _ = assertEquals(spans.size, 1)
        span = spans.head
        _ = assertEquals(span.getName, "tools/call get_forecast")
        _ = assertEquals(span.getKind.name(), "SERVER")
        _ = assertEquals(span.getAttributes.get(JAttributeKey.stringKey("mcp.method.name")), "tools/call")
        _ = assertEquals(span.getAttributes.get(JAttributeKey.stringKey("gen_ai.tool.name")), "get_forecast")
        _ = assertEquals(span.getAttributes.get(JAttributeKey.stringKey("gen_ai.operation.name")), "execute_tool")
        _ = assertEquals(span.getAttributes.get(JAttributeKey.stringKey("mcp.error_rung")), "ok")
        metrics = metricReader.collectAllMetrics().asScala.toList
        toolCalls = findMetric(metrics, McpSemantics.Metrics.ToolCalls)
        _ = assert(toolCalls.isDefined)
      yield ()
    }

  test("tools/call search_incidents with no matches stamps dud_suspect"):
    setup().flatMap { (server, spanExporter, metricReader) =>
      val msg = callToolMsg("search_incidents", JsonObject("query" -> Json.fromString("zzzz")))
      for
        res <- server.handleRecovering(msg)
        _ = assert(res.isDefined)
        spans = spanExporter.getFinishedSpanItems.asScala.toList
        _ = assertEquals(spans.size, 1)
        span = spans.head
        _ = assertEquals(span.getAttributes.get(JAttributeKey.stringKey("mcp.error_rung")), "dud_suspect")
        metrics = metricReader.collectAllMetrics().asScala.toList
        callsMetric = findMetric(metrics, McpSemantics.Metrics.ToolCalls)
        _ = assert(callsMetric.isDefined)
      yield ()
    }

  test("tools/call check_dependency throwing exception stamps tool_error"):
    setup().flatMap { (server, spanExporter, metricReader) =>
      val msg = callToolMsg("check_dependency", JsonObject.empty)
      for
        res <- server.handleRecovering(msg)
        _ = assert(res.isDefined)
        spans = spanExporter.getFinishedSpanItems.asScala.toList
        _ = assertEquals(spans.size, 1)
        span = spans.head
        _ = assertEquals(span.getAttributes.get(JAttributeKey.stringKey("mcp.error_rung")), "tool_error")
        _ = assertEquals(span.getAttributes.get(JAttributeKey.stringKey("error.type")), "tool_error")
        metrics = metricReader.collectAllMetrics().asScala.toList
        toolCalls = findMetric(metrics, McpSemantics.Metrics.ToolCalls)
        _ = assert(toolCalls.isDefined)
      yield ()
    }

  test("tools/call with unknown tool stamps protocol rung and records unknown in metric dimension"):
    setup().flatMap { (server, spanExporter, metricReader) =>
      val msg = callToolMsg("unregistered_tool", JsonObject.empty)
      for
        res <- server.handleRecovering(msg)
        _ = assert(res.isDefined)
        spans = spanExporter.getFinishedSpanItems.asScala.toList
        _ = assertEquals(spans.size, 1)
        span = spans.head
        // An unknown tool is a -32602 protocol error, so the call never reached a tool
        _ = assertEquals(span.getAttributes.get(JAttributeKey.stringKey("mcp.error_rung")), "protocol")
        metrics = metricReader.collectAllMetrics().asScala.toList
        toolCalls = findMetric(metrics, McpSemantics.Metrics.ToolCalls)
        _ = assert(toolCalls.isDefined)
        // Cardinailty protection: since "unregistered_tool" is not in registered tools, metric stamped as "unknown"
        hasUnknownDim = toolCalls.get.getLongSumData.getPoints.asScala.exists { pt =>
          pt.getAttributes.get(JAttributeKey.stringKey("mcp.tool.name")) == "unknown"
        }
        _ = assert(hasUnknownDim)
      yield ()
    }

  test("tools/list records tools_list.tokens_estimate"):
    setup().flatMap { (server, spanExporter, metricReader) =>
      val msg = new JsonRpcMessage.Request(
        RequestId.Num(2L),
        "tools/list",
        Some(Json.obj("_meta" -> meta))
      )
      for
        res <- server.handleRecovering(msg)
        _ = assert(res.isDefined)
        spans = spanExporter.getFinishedSpanItems.asScala.toList
        _ = assertEquals(spans.size, 1)
        span = spans.head
        _ = assertEquals(span.getName, "tools/list")
        metrics = metricReader.collectAllMetrics().asScala.toList
        listTokens = findMetric(metrics, McpSemantics.Metrics.ToolsListTokens)
        _ = assert(listTokens.isDefined)
      yield ()
    }

  private def findMetric(metrics: List[MetricData], name: String): Option[MetricData] =
    metrics.find(_.getName == name)
