package ironmcp
package otel

import cats.effect.IO
import io.circe.{Decoder, JsonObject}
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
import ironmcp.client.{ClientTransport, InputHandlers, McpClient}
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.OtelJava

import scala.jdk.CollectionConverters.*

class OtelClientSuite extends CatsEffectSuite:

  final case class EchoArgs(message: String :| Not[Empty]) derives Decoder, JsonSchemaOf

  private val echo = McpTool[EchoArgs]("echo", "Echoes.")(args => IO.pure(s"Echo: ${args.message: String}"))

  private def setup(): IO[(McpClient[IO], InMemorySpanExporter, InMemoryMetricReader)] =
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
        info = Implementation(name = "server", version = "0.1.0"),
        tools = Some(ToolSet.of(echo))
      )
      otelServer <- OtelServer.instrument(server, telemetry)
      baseTransport = new ClientTransport[IO]:
        def request(message: JsonRpcMessage.Request): IO[JsonRpcMessage] =
          otelServer.handleRecovering(message, transport = "inprocess").flatMap {
            case Some(reply) => IO.pure(reply)
            case None        => IO.raiseError(new RuntimeException("no reply"))
          }
        def notify(message: JsonRpcMessage.Notification): IO[Unit] =
          otelServer.handleRecovering(message, transport = "inprocess").void

      instrumentedTransport = OtelClient.instrument(baseTransport, telemetry, "inprocess")
      client <- McpClient[IO](
        instrumentedTransport,
        Implementation(name = "client", version = "0.1.0"),
        InputHandlers.none
      )
    yield (client, spanExporter, metricReader)

  test("OtelClient traces tool calls and injects trace context into _meta"):
    setup().flatMap { (client, spanExporter, metricReader) =>
      val args = JsonObject("message" -> io.circe.Json.fromString("hello"))
      for
        res <- client.callTool("echo", args)
        _ = assert(!res.isInstanceOf[InputRequiredResult])
        spans = spanExporter.getFinishedSpanItems.asScala.toList
        // Should have 2 spans: the server span and the client span
        _ = assertEquals(spans.size, 2)
        serverSpan = spans.find(_.getKind.name() == "SERVER").get
        clientSpan = spans.find(_.getKind.name() == "CLIENT").get

        // Both spans share the same trace ID (distributed trace propagation works!)
        _ = assertEquals(serverSpan.getTraceId, clientSpan.getTraceId)
        // Server span is parented by the client span
        _ = assertEquals(serverSpan.getParentSpanId, clientSpan.getSpanId)

        _ = assertEquals(clientSpan.getName, "tools/call echo")
        _ = assertEquals(clientSpan.getAttributes.get(JAttributeKey.stringKey("mcp.method.name")), "tools/call")
        _ = assertEquals(clientSpan.getAttributes.get(JAttributeKey.stringKey("gen_ai.tool.name")), "echo")

        metrics = metricReader.collectAllMetrics().asScala.toList
        cliDur = metrics.find(_.getName == McpSemantics.Metrics.ClientOperationDuration)
        _ = assert(cliDur.isDefined)
      yield ()
    }
