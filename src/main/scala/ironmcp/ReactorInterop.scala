package ironmcp

import cats.effect.IO
import cats.effect.std.Dispatcher
import reactor.core.publisher.Mono

import scala.jdk.FutureConverters.*

/** The MCP Java SDK's async surface is Reactor-based; ours is cats-effect.
  * This is the entire bridge — two functions, both total.
  */
object ReactorInterop:

  /** A `Mono` may complete empty, which surfaces as a null-completed future,
    * so the result is an `Option` rather than a lie.
    */
  def monoToIO[A](mono: => Mono[A]): IO[Option[A]] =
    IO.fromCompletableFuture(IO(mono.toFuture)).map(Option(_))

  /** `defer` keeps the effect lazy: nothing runs until the SDK subscribes. */
  def ioToMono[A](dispatcher: Dispatcher[IO])(io: IO[A]): Mono[A] =
    Mono.defer(() => Mono.fromCompletionStage(dispatcher.unsafeToFuture(io).asJava))
