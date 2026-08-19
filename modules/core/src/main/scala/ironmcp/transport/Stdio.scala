package ironmcp
package transport

import cats.effect.IO
import fs2.{text, Stream}
import ironmcp.server.McpServer

/** Newline-delimited JSON-RPC over stdin/stdout — what every local harness
  * (Claude Code, omp, agy) spawns.
  *
  * Diagnostics go to stderr, never stdout: anything on stdout that is not a
  * JSON-RPC message corrupts the stream.
  */
object Stdio:

  def serve(server: McpServer): IO[Unit] =
    fs2.io
      .stdinUtf8[IO](8192)
      .through(text.lines)
      .filter(_.trim.nonEmpty)
      .evalMap(line => respond(server, line))
      .unNone
      .map(_ + "\n")
      .through(text.utf8.encode)
      .through(fs2.io.stdout[IO])
      .compile
      .drain

  private def respond(server: McpServer, line: String): IO[Option[String]] =
    Wire.decode(line) match
      case Left(error)    => IO.pure(Some(Wire.errorResponse(error)))
      case Right(message) => server.handle(message).map(_.map(Wire.encode))

  /** Convenience for a main method. */
  def run(server: McpServer): Stream[IO, Nothing] = Stream.eval(serve(server)).drain
