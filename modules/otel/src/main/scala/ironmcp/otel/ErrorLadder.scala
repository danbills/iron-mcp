package ironmcp
package otel

import ironmcp.protocol.{CallToolResult, ContentBlock}

enum ErrorRung(val wire: String):
  case Ok extends ErrorRung("ok")
  case DudSuspect extends ErrorRung("dud_suspect")
  case ToolError extends ErrorRung("tool_error")
  case Protocol extends ErrorRung("protocol")

trait DudDetector:
  def isDud(toolName: String, result: CallToolResult): Boolean

  def orElse(other: DudDetector): DudDetector =
    (toolName: String, result: CallToolResult) => this.isDud(toolName, result) || other.isDud(toolName, result)

object DudDetector:
  val never: DudDetector = (_, _) => false

  /** Default dud heuristic: empty content blocks or explicit '(no matches)' text. */
  val default: DudDetector = (_, result) =>
    result.content.isEmpty || result.content.forall {
      case ContentBlock.Text(text, _, _) =>
        val trimmed = (text: String).trim
        trimmed == "(no matches)" || trimmed == "(empty)" || trimmed == "[]" || trimmed.isEmpty
      case _ => false
    }

  def matching(targetTool: String)(predicate: CallToolResult => Boolean): DudDetector =
    (name, result) => if name == targetTool then predicate(result) else false

  def apply(f: (String, CallToolResult) => Boolean): DudDetector =
    (name, result) => f(name, result)
