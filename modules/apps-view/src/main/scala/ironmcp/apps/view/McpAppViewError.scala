package ironmcp.apps.view

import ironmcp.protocol.JsonRpcError

/** The host answered a View request with a JSON-RPC error. */
final case class McpAppViewError(method: String, error: JsonRpcError)
    extends RuntimeException(s"$method failed (${error.code.code}): ${error.message}")
