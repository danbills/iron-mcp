package ironmcp
package otel

import cats.effect.Async
import cats.syntax.all.*
import fs2.{text, Pipe, Stream}
import ironmcp.server.McpServer
import ironmcp.transport.Wire

import java.nio.charset.StandardCharsets.UTF_8

object OtelStdio:

  /** The server as an instrumented stream of lines in, lines out. */
  def pipe[F[_]: Async](server: McpServer[F], telemetry: McpTelemetry[F]): Pipe[F, String, String] =
    val otelServer = new OtelServer(server, telemetry)
    _.filter(_.trim.nonEmpty).evalMap { line =>
      Wire.decode(line) match
        case Left(error) =>
          Async[F].pure(Some(Wire.errorResponse(error)))
        case Right(message) =>
          otelServer.handleRecovering(message, transport = "pipe", httpContext = None).map(_.map(Wire.encode))
    }.unNone

  /** Reads and writes the standard streams with OpenTelemetry instrumentation. */
  def serve[F[_]](server: McpServer[F], telemetry: McpTelemetry[F])(using F: Async[F]): F[Unit] =
    fs2.io
      .readInputStream(F.delay(System.in), 8192, closeAfterUse = false)
      .through(text.utf8.decode)
      .through(text.lines)
      .through(pipe(server, telemetry))
      .evalMap(line => F.blocking { System.out.write((line + "\n").getBytes(UTF_8)); System.out.flush() })
      .compile
      .drain

  /** Convenience for a main method. */
  def run[F[_]: Async](server: McpServer[F], telemetry: McpTelemetry[F]): Stream[F, Nothing] =
    Stream.eval(serve(server, telemetry)).drain
