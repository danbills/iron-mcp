package ironmcp
package transport

import cats.effect.{Async, LiftIO}
import cats.syntax.all.*
import fs2.{text, Stream}
import ironmcp.server.McpServer

/** Newline-delimited JSON-RPC over stdin/stdout — what every local harness
  * (Claude Code, omp, agy) spawns.
  *
  * Diagnostics go to stderr, never stdout: anything on stdout that is not a
  * JSON-RPC message corrupts the stream.
  *
  * The only place in core that needs more than `Applicative`: reading stdin
  * blocks (`Async`), and on Scala Native fs2 drives stdin/stdout through `IO`
  * (`LiftIO`). `IO` has both.
  */
object Stdio:

  // LiftIO is required by fs2's stdin/stdout on Scala Native and unused on the
  // JVM; this source is shared by both.
  @annotation.nowarn("msg=unused")
  def serve[F[_]: {Async, LiftIO}](server: McpServer[F]): F[Unit] =
    fs2.io
      .stdinUtf8[F](8192)
      .through(text.lines)
      .filter(_.trim.nonEmpty)
      .evalMap(line => respond(server, line))
      .unNone
      .map(_ + "\n")
      .through(text.utf8.encode)
      .through(fs2.io.stdout[F])
      .compile
      .drain

  private def respond[F[_]: Async](server: McpServer[F], line: String): F[Option[String]] =
    Wire.decode(line) match
      case Left(error)    => Async[F].pure(Some(Wire.errorResponse(error)))
      case Right(message) => server.handle(message).map(_.map(Wire.encode))

  /** Convenience for a main method. */
  def run[F[_]: {Async, LiftIO}](server: McpServer[F]): Stream[F, Nothing] = Stream.eval(serve(server)).drain
