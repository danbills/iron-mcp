package ironmcp
package http

import ironmcp.protocol.{JsonRpcMessage, RequestId}
import ironmcp.server.OnUnhandled
import org.typelevel.log4cats.Logger

/** An [[OnUnhandled]] hook that logs to a log4cats [[Logger]]: the method, the
  * request id and the exception with its stack trace. Never the params, which
  * may carry user data or credentials.
  *
  * {{{
  * McpServer(info, tools = Some(tools), onUnhandled = Some(UnhandledLog(logger)))
  * }}}
  */
object UnhandledLog:
  def apply[F[_]](logger: Logger[F]): OnUnhandled[F] =
    (message, error) => logger.error(error)(s"unhandled exception answering ${describe(message)}")

  /** `tools/call (id 7)`: enough to find the request, nothing it carried. */
  def describe(message: JsonRpcMessage): String = message match
    case JsonRpcMessage.Request(id, method, _)   => s"${method: String} (id ${show(id)})"
    case JsonRpcMessage.Notification(method, _)  => s"${method: String} (notification)"
    case JsonRpcMessage.Success(id, _)           => s"a response (id ${show(id)})"
    case JsonRpcMessage.Failure(id, _)           => s"an error response (id ${id.fold("null")(show)})"

  private def show(id: RequestId): String = id match
    case RequestId.Num(n)  => n.toString
    case RequestId.Text(t) => s"\"${t: String}\""
