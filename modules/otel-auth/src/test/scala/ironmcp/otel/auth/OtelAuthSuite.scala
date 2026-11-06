package ironmcp
package otel
package auth

import cats.effect.IO
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import ironmcp.auth.AuthEvent
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.OtelJava

import scala.jdk.CollectionConverters.*

class OtelAuthSuite extends CatsEffectSuite:

  private def event(allowed: Boolean, reason: String) =
    AuthEvent(0L, allowed, reason, None, "tools/call", "tool:x", Some("alice"), None, None, None, None, Set("a"), Set.empty)

  test("decisions are counted by outcome and reason"):
    for
      reader <- IO(InMemoryMetricReader.create())
      sdk    <- IO(OpenTelemetrySdk.builder().setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build()).build())
      otel   <- OtelJava.fromJOpenTelemetry[IO](sdk)
      meter  <- otel.meterProvider.get("test")
      tracer <- otel.tracerProvider.get("test")
      audit  <- OtelAuth.audit[IO](meter, tracer)
      _      <- audit.record(event(true, "ok"))
      _      <- audit.record(event(false, "audience"))
      _      <- audit.record(event(false, "audience"))
      data   <- IO(reader.collectAllMetrics().asScala.toList)
    yield
      val points = data.filter(_.getName == OtelAuth.Decisions).flatMap(_.getLongSumData.getPoints.asScala)
      val byReason = points.map(p => p.getAttributes.asMap.asScala.collectFirst { case (k, v) if k.getKey == "mcp.auth.reason" => v.toString }.get -> p.getValue).toMap
      assertEquals(byReason, Map("ok" -> 1L, "audience" -> 2L))
