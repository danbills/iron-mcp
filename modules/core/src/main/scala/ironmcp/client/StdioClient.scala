package ironmcp
package client

import cats.effect.{Async, Deferred, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{text, Stream}
import fs2.io.file.Path
import fs2.io.process.{ProcessBuilder, Processes}
import ironmcp.protocol.JsonRpcMessage
import ironmcp.server.NotificationSink

import scala.concurrent.duration.*

/** A server run as a child process, spoken to over its stdin and stdout: how
  * every local host starts an MCP server.
  *
  * The child's stderr is passed through to ours, since that is where a stdio
  * server logs. Closing the resource closes the child's stdin, which a
  * well-behaved server takes as its cue to exit; one that has not exited after
  * `exitGrace` is killed.
  *
  * Needs `Async`, to run the process and read its blocking streams.
  */
object StdioClient:

  def spawn[F[_]](
      command: String,
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty,
      workingDirectory: Option[String] = None,
      onNotification: Option[JsonRpcMessage.Notification => F[Unit]] = None,
      exitGrace: FiniteDuration = 2.seconds
  )(using F: Async[F]): Resource[F, ClientTransport[F]] =
    given Processes[F] = Processes.forAsync[F]
    Sigpipe.ignore()
    val builder = workingDirectory.foldLeft(ProcessBuilder(command, args).withExtraEnv(env))((b, dir) => b.withWorkingDirectory(Path(dir)))
    for
      process <- builder.spawn[F]
      lines   <- Resource.eval(Queue.unbounded[F, Option[String]])
      _ <- F.background(
             Stream.fromQueueNoneTerminated(lines).map(_ + "\n").through(text.utf8.encode).through(process.stdin).compile.drain
           )
      // Blocking writes rather than fs2.io.stderr: on Scala Native that goes through
      // epoll, which refuses a regular file, so a redirected stderr would stop the
      // pass-through and leave the child to block on a full pipe.
      stderrDone <- F.background(
                      process.stderr.chunks.evalMap(chunk => F.blocking { System.err.write(chunk.toArray); System.err.flush() }).compile.drain
                    )
      stdoutDone <- Resource.eval(Deferred[F, Unit])
      transport <- ClientTransport.lines(
                     process.stdout.through(text.utf8.decode).through(text.lines).onFinalize(stdoutDone.complete(()).void),
                     line => lines.offer(Some(line)),
                     onNotification.getOrElse(NotificationSink.discard[F])
                   )
      // Runs before the transport and the process are released: end stdin, give
      // the server a moment to exit on its own, and let both readers reach the end
      // of their streams. A reader that is cancelled instead destroys the process,
      // which on Scala Native throws once the child has already been reaped.
      _ <- Resource.onFinalize(
             lines.offer(None) >> F.timeoutTo(process.exitValue >> stdoutDone.get >> stderrDone.void, exitGrace, F.unit)
           )
    yield transport
