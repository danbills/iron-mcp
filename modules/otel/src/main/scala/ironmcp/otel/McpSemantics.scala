package ironmcp
package otel

import org.typelevel.otel4s.AttributeKey
import org.typelevel.otel4s.metrics.BucketBoundaries

object McpSemantics:

  /** The ActivitySource and Meter name used by the MCP C# SDK and MCP GenAI conventions. */
  val ScopeName: String = "Experimental.ModelContextProtocol"

  /** The Meter name used in the handbook sample for custom cost histograms and call counters. */
  val SampleMeterName: String = "OtelWiring.Sample"

  object Attributes:
    val MethodName: AttributeKey[String]         = AttributeKey.string("mcp.method.name")
    val ProtocolVersion: AttributeKey[String]    = AttributeKey.string("mcp.protocol.version")
    val SessionId: AttributeKey[String]          = AttributeKey.string("mcp.session.id")
    val RequestId: AttributeKey[String]          = AttributeKey.string("jsonrpc.request.id")
    val NetworkTransport: AttributeKey[String]   = AttributeKey.string("network.transport")
    val GenAiToolName: AttributeKey[String]      = AttributeKey.string("gen_ai.tool.name")
    val GenAiOperationName: AttributeKey[String] = AttributeKey.string("gen_ai.operation.name")
    val GenAiPromptName: AttributeKey[String]    = AttributeKey.string("gen_ai.prompt.name")
    val ResourceUri: AttributeKey[String]        = AttributeKey.string("mcp.resource.uri")
    val ErrorType: AttributeKey[String]          = AttributeKey.string("error.type")
    val ErrorRung: AttributeKey[String]          = AttributeKey.string("mcp.error_rung")
    val ToolName: AttributeKey[String]           = AttributeKey.string("mcp.tool.name")

  object Metrics:
    val ServerOperationDuration: String = "mcp.server.operation.duration"
    val ServerSessionDuration: String   = "mcp.server.session.duration"
    val ClientOperationDuration: String = "mcp.client.operation.duration"
    val ClientSessionDuration: String   = "mcp.client.session.duration"
    val ToolCalls: String               = "mcp.tool.calls"
    val ToolResultTokens: String        = "mcp.tool_result.tokens_estimate"
    val ToolsListTokens: String         = "mcp.tools_list.tokens_estimate"

  /** Explicit bucket boundaries specified in MCP semantic conventions for duration metrics (in seconds). */
  val ExplicitBucketBoundaries: BucketBoundaries =
    BucketBoundaries(Vector(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0))
