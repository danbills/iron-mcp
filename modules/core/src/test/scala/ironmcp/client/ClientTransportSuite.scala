package ironmcp
package client

import cats.ApplicativeThrow
import cats.effect.std.Queue
import cats.effect.{IO, Resource}
import fs2.Stream
import io.circe.Json
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.ServerHandler
import ironmcp.transport.Wire
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** Both client transports, at their edges: replies a server shouldn't send, streams that end or fail, and sends
  * that fail.
  */
class ClientTransportSuite extends CatsEffectSuite:

  private val request = new JsonRpcMessage.Request(RequestId.Num(1), "x/ping", None)

  /** A handler that answers every request with `reply`, and records notifications. */
  private def answering(reply: Option[JsonRpcMessage], seen: cats.effect.Ref[IO, List[JsonRpcMessage]]): ServerHandler[IO] =
    new ServerHandler[IO]:
      def handle(message: JsonRpcMessage) = seen.update(_ :+ message).as(reply)
      def handleRecovering(message: JsonRpcMessage)(using ApplicativeThrow[IO]) = handle(message)

  // ---- inProcess ------------------------------------------------------------------------------------------------

  test("inProcess: a request the server doesn't answer is Malformed"):
    for
      seen  <- IO.ref(List.empty[JsonRpcMessage])
      error <- ClientTransport.inProcess(answering(None, seen)).request(request).attempt
    yield assertEquals(error.left.toOption.map(_.getMessage), Some("malformed response: no reply to x/ping"))

  test("inProcess: a reply that breaks the protocol (an empty error message) is Malformed"):
    val broken = JsonRpcMessage.Failure(Some(RequestId.Num(1)), JsonRpcError(ErrorCode.InternalError, "".asInstanceOf[NonEmptyString]))
    for
      seen  <- IO.ref(List.empty[JsonRpcMessage])
      error <- ClientTransport.inProcess(answering(Some(broken), seen)).request(request).attempt
    yield assert(error.left.exists(_.isInstanceOf[McpClientError.Malformed]), error.toString)

  test("inProcess: notify reaches the server through the wire"):
    val note = new JsonRpcMessage.Notification("notifications/initialized", Some(Json.obj()))
    for
      seen <- IO.ref(List.empty[JsonRpcMessage])
      _    <- ClientTransport.inProcess(answering(None, seen)).notify(note)
      got  <- seen.get
    yield assertEquals(got, List(note))

  // ---- lines ----------------------------------------------------------------------------------------------------

  /** A lines transport whose incoming lines the test writes (None ends the stream), with what it sent. */
  private def wired(send: String => IO[Unit] = _ => IO.unit): Resource[IO, (ClientTransport[IO], Queue[IO, Option[Either[Throwable, String]]], IO[List[JsonRpcMessage.Notification]])] =
    for
      incoming <- Resource.eval(Queue.unbounded[IO, Option[Either[Throwable, String]]])
      notes    <- Resource.eval(IO.ref(List.empty[JsonRpcMessage.Notification]))
      lines     = Stream.fromQueueNoneTerminated(incoming).flatMap(Stream.fromEither[IO](_))
      t        <- ClientTransport.lines[IO](lines, send, n => notes.update(_ :+ n))
    yield (t, incoming, notes.get)

  test("lines: a failure reply completes its request; stray lines and server requests are ignored"):
    wired().use { (t, incoming, notes) =>
      val failure = JsonRpcMessage.Failure(Some(RequestId.Num(1)), JsonRpcError(ErrorCode.MethodNotFound, "nope"))
      for
        pending <- t.request(request).start
        _       <- IO.sleep(20.millis)
        _       <- incoming.offer(Some(Right("not json")))
        _       <- incoming.offer(Some(Right(Wire.encode(JsonRpcMessage.Request(RequestId.Num(5), "roots/list", None)))))
        _       <- incoming.offer(Some(Right(Wire.encode(JsonRpcMessage.Notification("notifications/progress", None)))))
        _       <- incoming.offer(Some(Right(Wire.encode(failure))))
        reply   <- pending.joinWithNever
        got     <- notes
      yield
        assertEquals(reply, failure)
        assertEquals(got.map(_.method: String), List("notifications/progress"))
    }

  test("lines: a stream that fails fails every pending request with the reason, and later ones at once"):
    wired().use { (t, incoming, _) =>
      for
        pending <- t.request(request).start
        _       <- IO.sleep(20.millis)
        _       <- incoming.offer(Some(Left(RuntimeException("pipe burst"))))
        first   <- pending.joinWithNever.attempt
        later   <- t.request(request.copy(id = RequestId.Num(2))).attempt
      yield
        assertEquals(first.left.toOption, Some(McpClientError.Closed("read failed: pipe burst")))
        assertEquals(later.left.toOption, Some(McpClientError.Closed("read failed: pipe burst")))
    }

  test("lines: a send that fails fails its request and leaves nothing pending"):
    wired(_ => IO.raiseError(RuntimeException("stdin closed"))).use { (t, incoming, _) =>
      for
        error <- t.request(request).attempt
        // a late reply to the withdrawn request finds nothing waiting for it
        _     <- incoming.offer(Some(Right(Wire.encode(JsonRpcMessage.Success(RequestId.Num(1), Json.obj())))))
      yield assertEquals(error.left.toOption.map(_.getMessage), Some("stdin closed"))
    }

  test("lines: notify writes one line"):
    for
      sent <- IO.ref(List.empty[String])
      _    <- wired(line => sent.update(_ :+ line)).use((t, _, _) => t.notify(JsonRpcMessage.Notification("notifications/initialized", None)))
      got  <- sent.get
    yield assertEquals(got, List("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
