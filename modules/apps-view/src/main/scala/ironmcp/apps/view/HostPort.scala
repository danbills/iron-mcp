package ironmcp.apps.view

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Concurrent, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.scalajs.{convertJsToJson, convertJsonToJs}
import org.scalajs.dom

import scala.scalajs.js

/** How a View exchanges JSON-RPC messages with its host. */
trait HostPort[F[_]]:
  def send(message: Json): F[Unit]
  def incoming: Stream[F, Json]

object HostPort:

  /** The real one: `postMessage` to `window.parent`, and the `message` events
    * it posts back. Only messages from the parent window that are JSON-RPC
    * 2.0 get through. The listener is removed when the resource is released.
    */
  def windowParent[F[_]](using F: Async[F]): Resource[F, HostPort[F]] =
    for
      dispatcher <- Dispatcher.sequential[F]
      queue      <- Resource.eval(Queue.unbounded[F, Json])
      _ <- Resource.make(F.delay {
             val listener: js.Function1[dom.MessageEvent, Unit] = event =>
               if js.special.strictEquals(event.source, dom.window.parent) then
                 convertJsToJson(event.data.asInstanceOf[js.Any]).toOption
                   .filter(_.hcursor.get[String]("jsonrpc").contains("2.0"))
                   .foreach(json => dispatcher.unsafeRunAndForget(queue.offer(json)))
             dom.window.addEventListener("message", listener)
             listener
           })(listener => F.delay(dom.window.removeEventListener("message", listener)))
    yield new HostPort[F]:
      def send(message: Json): F[Unit] = F.delay(dom.window.parent.postMessage(convertJsonToJs(message), "*"))
      def incoming: Stream[F, Json]    = Stream.fromQueueUnterminated(queue)

  /** Both ends of an in-memory channel, for testing a View without a browser:
    * `port` is the View's side; the test plays the host with `toView` and
    * `fromView`.
    */
  final class InMemory[F[_]: Concurrent] private[HostPort] (toViewQueue: Queue[F, Json], fromViewQueue: Queue[F, Json]):
    val port: HostPort[F] = new HostPort[F]:
      def send(message: Json): F[Unit] = fromViewQueue.offer(message)
      def incoming: Stream[F, Json]    = Stream.fromQueueUnterminated(toViewQueue)

    /** Delivers a message from the host to the View. */
    def toView(message: Json): F[Unit] = toViewQueue.offer(message)

    /** The next message the View sent. */
    def fromView: F[Json] = fromViewQueue.take

  def inMemory[F[_]: Concurrent]: F[InMemory[F]] =
    (Queue.unbounded[F, Json], Queue.unbounded[F, Json]).mapN(InMemory(_, _))
