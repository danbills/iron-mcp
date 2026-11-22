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
    AuthEvent(
      0L,
      allowed,
      reason,
      None,
      "tools/call",
      "tool:x",
      Some("alice"),
      Some("client-1"),
      Some("https://idp.example"),
      Some("jkt-1"),
      None,
      Set("a"),
      Set("a")
    )

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
      val byReason = points.flatMap(p => p.getAttributes.asMap.asScala.collectFirst { case (k, v) if k.getKey == "mcp.auth.reason" => v.toString -> p.getValue }).toMap
      assertEquals(byReason.size, points.size, "every point carries mcp.auth.reason")
      assertEquals(byReason, Map("ok" -> 1L, "audience" -> 2L))
