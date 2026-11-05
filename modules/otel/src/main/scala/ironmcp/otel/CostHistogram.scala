package ironmcp
package otel

import io.circe.syntax.*
import ironmcp.protocol.{CallToolResult, ContentBlock, ListToolsResult}

object CostHistogram:

  /** Estimates tokens as UTF-16 characters / 4 across text blocks (Section 10.2.6). */
  def toolResultTokens(result: CallToolResult): Long =
    val chars = result.content.foldLeft(0L) {
      case (acc, ContentBlock.Text(text, _, _)) => acc + (text: String).length.toLong
      case (acc, _)                             => acc
    }
    chars / 4L

  /** Estimates tokens as UTF-16 characters / 4 across tools/list response (Section 10.2.6). */
  def toolsListTokens(result: ListToolsResult): Long =
    val chars = result.tools.foldLeft(0L) { (acc, tool) =>
      val nameLen   = (tool.name: String).length.toLong
      val descLen   = tool.description.fold(0L)(d => (d: String).length.toLong)
      val schemaLen = tool.inputSchema.asJson.noSpaces.length.toLong
      acc + nameLen + descLen + schemaLen
    }
    chars / 4L
