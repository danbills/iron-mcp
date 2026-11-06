package ironmcp
package otel

import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.trace.Tracer

final class McpTelemetry[F[_]](
    val tracer: Tracer[F],
    val metrics: McpMetrics[F],
    val dudDetector: DudDetector = DudDetector.default,
    val knownTools: Set[String] = Set.empty
):
  def withDudDetector(detector: DudDetector): McpTelemetry[F] =
    new McpTelemetry(tracer, metrics, detector, knownTools)

  def withKnownTools(tools: Set[String]): McpTelemetry[F] =
    new McpTelemetry(tracer, metrics, dudDetector, tools)

object McpTelemetry:

  def apply[F[_]](
      tracer: Tracer[F],
      metrics: McpMetrics[F],
      dudDetector: DudDetector = DudDetector.default,
      knownTools: Set[String] = Set.empty
  ): McpTelemetry[F] =
    new McpTelemetry(tracer, metrics, dudDetector, knownTools)

  /** Creates McpTelemetry using an OtelJava instance. */
  def fromOtelJava[F[_]: Async](
      otel: OtelJava[F],
      dudDetector: DudDetector = DudDetector.default,
      knownTools: Set[String] = Set.empty
  ): F[McpTelemetry[F]] =
    for
      tracer      <- otel.tracerProvider.get(McpSemantics.ScopeName)
      mcpMeter    <- otel.meterProvider.get(McpSemantics.ScopeName)
      sampleMeter <- otel.meterProvider.get(McpSemantics.SampleMeterName)
      metrics     <- McpMetrics.create[F](mcpMeter, Some(sampleMeter))
    yield new McpTelemetry[F](tracer, metrics, dudDetector, knownTools)

  /** Creates McpTelemetry from Global OpenTelemetry. */
  def global[F[_]: Async](using LocalProvider[F, Context]): F[McpTelemetry[F]] =
    OtelJava.global[F].flatMap(fromOtelJava[F](_))

  def noop[F[_]: Async](using LocalProvider[F, Context]): F[McpTelemetry[F]] =
    OtelJava.noop[F].flatMap(fromOtelJava[F](_))
