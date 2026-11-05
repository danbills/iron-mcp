package ironmcp
package otel

import cats.Monad
import cats.syntax.all.*
import org.typelevel.otel4s.metrics.{Counter, Histogram, Meter}

final class McpMetrics[F[_]](
    val serverOperationDuration: Histogram[F, Double],
    val serverSessionDuration: Histogram[F, Double],
    val clientOperationDuration: Histogram[F, Double],
    val clientSessionDuration: Histogram[F, Double],
    val toolCalls: Counter[F, Long],
    val toolResultTokens: Histogram[F, Long],
    val toolsListTokens: Histogram[F, Long]
)

object McpMetrics:

  /** Creates MCP metrics conforming to MCP GenAI semantic conventions and handbook Section 10.2.
    *
    * @param mcpMeter    The meter for standard MCP telemetry (typically "Experimental.ModelContextProtocol").
    * @param sampleMeter The meter for sample cost/rung instruments (typically "OtelWiring.Sample").
    */
  def create[F[_]](
      mcpMeter: Meter[F],
      sampleMeter: Option[Meter[F]] = None
  )(using F: Monad[F]): F[McpMetrics[F]] =
    val customMeter = sampleMeter.getOrElse(mcpMeter)
    for
      srvOpDur <- mcpMeter
                    .histogram[Double](McpSemantics.Metrics.ServerOperationDuration)
                    .withUnit("s")
                    .withDescription("Duration of MCP server operations.")
                    .withExplicitBucketBoundaries(McpSemantics.ExplicitBucketBoundaries)
                    .create
      srvSessDur <- mcpMeter
                      .histogram[Double](McpSemantics.Metrics.ServerSessionDuration)
                      .withUnit("s")
                      .withDescription("Duration of MCP server sessions.")
                      .create
      cliOpDur <- mcpMeter
                    .histogram[Double](McpSemantics.Metrics.ClientOperationDuration)
                    .withUnit("s")
                    .withDescription("Duration of MCP client operations.")
                    .withExplicitBucketBoundaries(McpSemantics.ExplicitBucketBoundaries)
                    .create
      cliSessDur <- mcpMeter
                      .histogram[Double](McpSemantics.Metrics.ClientSessionDuration)
                      .withUnit("s")
                      .withDescription("Duration of MCP client sessions.")
                      .create
      calls <- customMeter
                 .counter[Long](McpSemantics.Metrics.ToolCalls)
                 .withUnit("{call}")
                 .withDescription("tools/call requests by tool and error-ladder rung (mcp.error_rung).")
                 .create
      toolResTok <- customMeter
                      .histogram[Long](McpSemantics.Metrics.ToolResultTokens)
                      .withUnit("{token}")
                      .withDescription(
                        "Rough size index of tool-result text: UTF-16 chars / 4. Not a tokenizer count; stands in for a reference-tokenizer histogram."
                      )
                      .create
      toolsListTok <- customMeter
                        .histogram[Long](McpSemantics.Metrics.ToolsListTokens)
                        .withUnit("{token}")
                        .withDescription(
                          "Rough size index of the tools/list response (names, descriptions, input schemas): UTF-16 chars / 4. Not a tokenizer count. The standing tax."
                        )
                        .create
    yield new McpMetrics[F](
      serverOperationDuration = srvOpDur,
      serverSessionDuration = srvSessDur,
      clientOperationDuration = cliOpDur,
      clientSessionDuration = cliSessDur,
      toolCalls = calls,
      toolResultTokens = toolResTok,
      toolsListTokens = toolsListTok
    )

  def noop[F[_]: Monad]: F[McpMetrics[F]] =
    create(Meter.noop[F])
