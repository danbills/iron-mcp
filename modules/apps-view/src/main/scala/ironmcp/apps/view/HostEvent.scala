package ironmcp.apps.view

import io.circe.{Json, JsonObject}
import ironmcp.protocol.CallToolResult

/** A notification from the host about the tool call this View renders. */
enum HostEvent:
  /** The tool's complete arguments (`ui/notifications/tool-input`). */
  case ToolInput(arguments: JsonObject)

  /** Arguments still streaming in (`ui/notifications/tool-input-partial`):
    * render a preview, nothing that cannot be redone.
    */
  case ToolInputPartial(arguments: JsonObject)

  /** The tool's result (`ui/notifications/tool-result`). */
  case ToolResult(result: CallToolResult)

  /** The call was cancelled (`ui/notifications/tool-cancelled`). */
  case ToolCancelled(reason: Option[String])

  /** A notification above whose params did not decode. */
  case Unreadable(method: String, params: Json, reason: String)
