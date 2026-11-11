package ironmcp
package server

import cats.Applicative
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*

/** Where a transport puts the notifications a request emits while it runs. */
type NotificationSink[F[_]] = JsonRpcMessage.Notification => F[Unit]

object NotificationSink:
  /** Drops every notification: for a request served with nowhere to put them. */
  def discard[F[_]](using F: Applicative[F]): NotificationSink[F] = _ => F.unit

/** What a running tool can tell the client before it answers.
  *
  * Only progress: revision 2026-07-28 deprecates the `logging` capability, so
  * there is no `notifications/message` here. A request that carries no
  * `progressToken` asked for no progress, and `progress` does nothing.
  *
  * The transport decides whether a notification reaches the client: stdio
  * writes it before the reply, Streamable HTTP switches the response to an SSE
  * stream when the client accepts one, and a transport with nowhere to put it
  * drops it.
  */
final class Emit[F[_]] private (val progressToken: Option[ProgressToken], sink: NotificationSink[F])(using F: Applicative[F]):

  /** `notifications/progress`. Per the spec `progress` should increase with
    * each call; a negative value is sent as 0.
    */
  def progress(progress: Double, total: Option[Double] = None, message: Option[String] = None): F[Unit] =
    progressToken match
      case None => F.unit
      case Some(token) =>
        val params = ProgressParams(
          progressToken = token,
          progress = progress.max(0.0).assume[GreaterEqual[0.0]],
          total = total,
          message = message.filter(_.nonEmpty).map(_.assume[Not[Empty]])
        )
        sink(JsonRpcMessage.Notification("notifications/progress", Some(params.asJson.deepDropNullValues)))

object Emit:

  /** Emits nothing: for a request served without a sink. */
  def none[F[_]: Applicative]: Emit[F] = new Emit[F](None, NotificationSink.discard[F])

  def apply[F[_]: Applicative](meta: RequestMeta, sink: NotificationSink[F]): Emit[F] = new Emit[F](meta.progressToken, sink)
