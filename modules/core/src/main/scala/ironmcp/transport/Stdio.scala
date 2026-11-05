package ironmcp
package transport

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all.*
import fs2.{text, Pipe, Stream}
import ironmcp.server.ServerHandler

import java.nio.charset.StandardCharsets.UTF_8

/** Newline-delimited JSON-RPC over stdin/stdout — what every local harness
  * (Claude Code, omp, agy) spawns.
  *
  * Diagnostics go to stderr, never stdout: anything on stdout that is not a
  * JSON-RPC message corrupts the stream.
  *
  * Serving needs `Async`, since reading stdin blocks; [[pipe]], the server as
  * lines in and lines out, needs only `MonadThrow`.
  */
object Stdio:

  /** Reads and writes the standard streams with plain blocking calls rather
    * than `fs2.io.stdin`/`stdout`: on Scala Native those go through epoll, which
    * refuses a regular file, so `server < requests > replies` would fail.
    */
  def serve[F[_]](server: ServerHandler[F])(using F: Async[F]): F[Unit] =
    fs2.io
      .readInputStream(F.delay(System.in), 8192, closeAfterUse = false)
      .through(text.utf8.decode)
      .through(text.lines)
      .through(pipe(server))
      .evalMap(line => F.blocking { System.out.write((line + "\n").getBytes(UTF_8)); System.out.flush() })
      .compile
      .drain

  /** The server as a stream of lines in, lines out: what [[serve]] runs between
    * stdin and stdout. A client can be joined to it in memory, with no process.
    */
  def pipe[F[_]: MonadThrow](server: ServerHandler[F]): Pipe[F, String, String] =
    _.filter(_.trim.nonEmpty).evalMap(line => respond(server, line)).unNone

  private def respond[F[_]: MonadThrow](server: ServerHandler[F], line: String): F[Option[String]] =
    Wire.decode(line) match
      case Left(error)    => MonadThrow[F].pure(Some(Wire.errorResponse(error)))
      case Right(message) => server.handleRecovering(message).map(_.map(Wire.encode))

  /** Convenience for a main method. */
  def run[F[_]: Async](server: ServerHandler[F]): Stream[F, Nothing] = Stream.eval(serve(server)).drain
