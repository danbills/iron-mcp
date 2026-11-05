package ironmcp
package otel

import ironmcp.protocol.{CallToolResult, ContentBlock}
import munit.FunSuite

class ErrorLadderSuite extends FunSuite:

  test("ErrorRung wire names match the MCP Engineer Handbook specification"):
    assertEquals(ErrorRung.Ok.wire, "ok")
    assertEquals(ErrorRung.DudSuspect.wire, "dud_suspect")
    assertEquals(ErrorRung.ToolError.wire, "tool_error")
    assertEquals(ErrorRung.Protocol.wire, "protocol")

  test("DudDetector.default classifies empty content or (no matches) as dud"):
    val emptyResult = CallToolResult(content = Nil)
    val noMatchesResult = CallToolResult(content = List(ContentBlock.Text("(no matches)")))
    val emptyStrResult = CallToolResult(content = List(ContentBlock.Text("   ")))
    val validResult = CallToolResult(content = List(ContentBlock.Text("found 3 results")))

    assert(DudDetector.default.isDud("search", emptyResult))
    assert(DudDetector.default.isDud("search", noMatchesResult))
    assert(DudDetector.default.isDud("search", emptyStrResult))
    assert(!DudDetector.default.isDud("search", validResult))

  test("DudDetector.matching allows per-tool heuristics"):
    val detector = DudDetector.matching("search_incidents") { res =>
      res.content.exists {
        case ContentBlock.Text(t, _, _) => t.contains("zero hits")
        case _                          => false
      }
    }
    val hit = CallToolResult(content = List(ContentBlock.Text("zero hits found")))
    val miss = CallToolResult(content = List(ContentBlock.Text("1 hit found")))

    assert(detector.isDud("search_incidents", hit))
    assert(!detector.isDud("search_incidents", miss))
    assert(!detector.isDud("other_tool", hit))

  test("DudDetector.orElse combines multiple detectors"):
    val d1 = DudDetector.matching("tool_a")(_.content.isEmpty)
    val d2 = DudDetector.matching("tool_b")(_.content.isEmpty)
    val combined = d1.orElse(d2)

    assert(combined.isDud("tool_a", CallToolResult(Nil)))
    assert(combined.isDud("tool_b", CallToolResult(Nil)))
    assert(!combined.isDud("tool_c", CallToolResult(Nil)))
