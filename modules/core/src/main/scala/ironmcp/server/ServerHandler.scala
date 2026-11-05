package ironmcp
package server

import cats.ApplicativeThrow
import ironmcp.protocol.JsonRpcMessage

/** Common abstraction for MCP request handling (plain [[McpServer]],
  * Tasks extension wrapper, etc.).
  */
trait ServerHandler[F[_]]:
  def handle(message: JsonRpcMessage): F[Option[JsonRpcMessage]]
  def handleRecovering(message: JsonRpcMessage)(using ApplicativeThrow[F]): F[Option[JsonRpcMessage]]

  /** [[handle]], with somewhere to put the notifications the request emits
    * while it runs (progress). Still one message in, one reply out: the sink
    * belongs to the transport, not to the server. A handler that emits nothing
    * may keep this default.
    */
  def handleEmitting(message: JsonRpcMessage, @annotation.unused emit: NotificationSink[F]): F[Option[JsonRpcMessage]] =
    handle(message)

  /** [[handleRecovering]], with a sink as in [[handleEmitting]]. */
  def handleEmittingRecovering(message: JsonRpcMessage, @annotation.unused emit: NotificationSink[F])(using
      ApplicativeThrow[F]
  ): F[Option[JsonRpcMessage]] =
    handleRecovering(message)
