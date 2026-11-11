package ironmcp
package client

import cats.MonadThrow
import cats.effect.{Concurrent, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.{NotificationSink, ServerHandler}
import ironmcp.transport.Wire

/** How a client reaches a server: send a request, get its response.
  *
  * Revision 2026-07-28 has no session and servers never send requests, so this
  * is all a transport is. The response is a `Success` or a `Failure`;
  * [[McpClient]] turns a failure into an error.
  */
trait ClientTransport[F[_]]:
  def request(message: JsonRpcMessage.Request): F[JsonRpcMessage]
  def notify(message: JsonRpcMessage.Notification): F[Unit]

object ClientTransport:

  /** A server in the same process. Messages still go through [[Wire]] both
    * ways, so what is tested is what a remote client would see.
    */
  def inProcess[F[_]](server: ServerHandler[F])(using F: MonadThrow[F]): ClientTransport[F] =
    inProcess(server, NotificationSink.discard[F])

  /** [[inProcess]], with the notifications a request emits (progress) going
    * to `onNotification` before its reply returns.
    */
  def inProcess[F[_]](server: ServerHandler[F], onNotification: JsonRpcMessage.Notification => F[Unit])(using
      F: MonadThrow[F]
  ): ClientTransport[F] = new ClientTransport[F]:
    private def overWire(message: JsonRpcMessage): F[JsonRpcMessage] =
      F.fromEither(Wire.decode(Wire.encode(message)).leftMap(e => McpClientError.Malformed(e.message: String)))

    // A notification survives the round trip as a notification; `collect` only narrows the type.
    private val delivered: JsonRpcMessage.Notification => F[Unit] = notification =>
      overWire(notification).flatMap(message => Option(message).collect { case n: JsonRpcMessage.Notification => n }.traverse_(onNotification))

    def request(message: JsonRpcMessage.Request): F[JsonRpcMessage] =
      overWire(message).flatMap(server.handleEmittingRecovering(_, delivered)).flatMap {
        case Some(reply) => overWire(reply)
        case None        => F.raiseError(McpClientError.Malformed(s"no reply to ${message.method: String}"))
      }

    def notify(message: JsonRpcMessage.Notification): F[Unit] =
      overWire(message).flatMap(server.handleRecovering).void

  /** Newline-delimited JSON-RPC over a pair of streams: the stdio framing,
    * without the process (see [[StdioClient]] for that).
    *
    * Requests may be in flight concurrently; replies are matched to them by id,
    * in whatever order they come. A server notification goes to
    * `onNotification`. When `incoming` ends, every pending request fails with
    * [[McpClientError.Closed]]. A request whose caller is cancelled is
    * withdrawn with `notifications/cancelled`, as the stdio binding specifies.
    *
    * `send` writes one line; it must be safe to call concurrently (a queue
    * offer is).
    */
  def lines[F[_]](
      incoming: Stream[F, String],
      send: String => F[Unit],
      onNotification: JsonRpcMessage.Notification => F[Unit]
  )(using F: Concurrent[F]): Resource[F, ClientTransport[F]] =
    type Pending = Map[RequestId, Deferred[F, Either[Throwable, JsonRpcMessage]]]
    for
      pending <- Resource.eval(Ref.of[F, Pending](Map.empty))
      closed  <- Resource.eval(Ref.of[F, Option[String]](None))
      complete = (id: RequestId, reply: JsonRpcMessage) =>
                   pending.modify(all => (all - id, all.get(id))).flatMap(_.traverse_(_.complete(Right(reply)).void))
      shutdown = (reason: String) =>
                   closed.set(Some(reason)) >>
                     pending.getAndSet(Map.empty).flatMap(_.values.toList.traverse_(_.complete(Left(McpClientError.Closed(reason))).void))
      reader = incoming
                 .filter(_.trim.nonEmpty)
                 .evalMap { line =>
                   Wire.decode(line) match
                     case Right(reply @ JsonRpcMessage.Success(id, _))       => complete(id, reply)
                     case Right(reply @ JsonRpcMessage.Failure(Some(id), _)) => complete(id, reply)
                     case Right(notification: JsonRpcMessage.Notification)   => onNotification(notification)
                     // A request from the server (none in this revision), an error
                     // with no id, or a line that is not JSON-RPC: nothing waits for it.
                     case _ => F.unit
                 }
                 .compile
                 .drain
                 .attempt
                 .flatMap(outcome => shutdown(outcome.fold(e => s"read failed: ${e.getMessage}", _ => "the server closed the stream")))
      _ <- F.background(reader)
      _ <- Resource.onFinalize(shutdown("the client was closed"))
    yield new ClientTransport[F]:
      def request(message: JsonRpcMessage.Request): F[JsonRpcMessage] =
        F.uncancelable { poll =>
          for
            reply <- Deferred[F, Either[Throwable, JsonRpcMessage]]
            gone  <- closed.get
            _     <- gone.traverse_(reason => F.raiseError(McpClientError.Closed(reason)))
            _     <- pending.update(_.updated(message.id, reply))
            _     <- send(Wire.encode(message)).onError(_ => pending.update(_ - message.id))
            got <- poll(reply.get).onCancel(
                     pending.update(_ - message.id) >>
                       send(
                         Wire.encode(
                           JsonRpcMessage.Notification(
                             "notifications/cancelled",
                             Some(Json.obj("requestId" -> io.circe.Encoder[RequestId].apply(message.id), "reason" -> Json.fromString("cancelled by the client")))
                           )
                         )
                       ).attempt.void
                   )
            result <- F.fromEither(got)
          yield result
        }

      def notify(message: JsonRpcMessage.Notification): F[Unit] = send(Wire.encode(message))
