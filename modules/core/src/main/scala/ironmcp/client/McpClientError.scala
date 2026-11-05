package ironmcp
package client

import ironmcp.protocol.JsonRpcError

import scala.concurrent.duration.FiniteDuration

/** Everything a client call can fail with. A tool that fails at its job is not
  * one of these: that is a `CallToolResult` with `isError`, for the model to read.
  */
sealed abstract class McpClientError(message: String) extends RuntimeException(message)

object McpClientError:

  /** The server answered with a JSON-RPC error. */
  final case class Rpc(error: JsonRpcError) extends McpClientError(s"${error.code.code}: ${error.message: String}")

  /** An HTTP response that carried no JSON-RPC answer: a 401 or 403 with its
    * `WWW-Authenticate` challenge, a proxy's 429, a 5xx.
    */
  final case class Http(status: Int, wwwAuthenticate: List[String], body: String)
      extends McpClientError(s"HTTP $status${wwwAuthenticate.headOption.fold("")(c => s" ($c)")}")

  /** The connection is gone: the server process exited or the stream ended. */
  final case class Closed(reason: String) extends McpClientError(s"connection closed: $reason")

  /** The server sent something that is not the answer the protocol promises. */
  final case class Malformed(reason: String) extends McpClientError(s"malformed response: $reason")

  /** Still `input_required` after the allowed number of rounds. */
  final case class RetryBudgetExhausted(rounds: Int) extends McpClientError(s"still input_required after $rounds rounds")

  /** Gathering input took longer than its deadline, prompts included. */
  final case class GatherTimedOut(after: FiniteDuration) extends McpClientError(s"tool call still gathering input after $after")

  /** The server asked for something this client has no handler for. */
  final case class UnsupportedInputRequest(key: String, method: String)
      extends McpClientError(s"input request '$key' needs $method, which this client does not handle")
