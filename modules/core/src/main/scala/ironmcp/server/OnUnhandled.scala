package ironmcp
package server

import cats.{Applicative, ApplicativeThrow}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*

/** Where an exception goes when a handler raised it instead of answering.
  *
  * A recovering handler replies `-32603 internal error` without the
  * exception's message, which may hold internals the client should not see.
  * This hook is the server's side of that: the message and the exception, to
  * log or record. An [[McpException]] is an answer the handler chose, so it
  * never reaches the hook.
  *
  * {{{
  * McpServer(info, tools = Some(tools), onUnhandled = Some((message, error) => logger.error(error)(s"unhandled: $message")))
  * }}}
  */
trait OnUnhandled[F[_]]:
  def apply(message: JsonRpcMessage, error: Throwable): F[Unit]

object OnUnhandled:
  def noop[F[_]: Applicative]: OnUnhandled[F] = (_, _) => Applicative[F].unit

  def both[F[_]: Applicative](a: OnUnhandled[F], b: OnUnhandled[F]): OnUnhandled[F] =
    (message, error) => a(message, error) *> b(message, error)

  /** `fa`, with a raised exception turned into its error reply. The hook sees
    * every exception but an [[McpException]]; if the hook itself fails, the
    * reply still goes out.
    */
  def recover[F[_]](message: JsonRpcMessage, hook: OnUnhandled[F])(fa: F[Option[JsonRpcMessage]])(using
      F: ApplicativeThrow[F]
  ): F[Option[JsonRpcMessage]] =
    fa.handleErrorWith { error =>
      val reported = error match
        case McpException(_) => F.unit
        case _               => hook(message, error).handleError(_ => ())
      reported.as(reply(message, error))
    }

  /** The reply a recovering handler sends for `error`: the [[McpException]]'s
    * error, or `-32603` with no detail. A notification gets none.
    */
  def reply(message: JsonRpcMessage, error: Throwable): Option[JsonRpcMessage] =
    val rpc = error match
      case McpException(rpc) => rpc
      case _                 => JsonRpcError(ErrorCode.InternalError, "internal error")
    message match
      case JsonRpcMessage.Request(id, _, _) => Some(JsonRpcMessage.Failure(Some(id), rpc))
      case _                                => None
