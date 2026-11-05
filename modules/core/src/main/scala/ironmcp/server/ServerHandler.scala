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
