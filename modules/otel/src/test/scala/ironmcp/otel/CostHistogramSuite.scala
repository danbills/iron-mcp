package ironmcp
package otel

import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.FunSuite

class CostHistogramSuite extends FunSuite:

  test("toolResultTokens divides UTF-16 character count by 4"):
    val text = "12345678" // 8 chars -> 2 tokens
    val result = CallToolResult(content = List(ContentBlock.Text(text)))
    assertEquals(CostHistogram.toolResultTokens(result), 2L)

  test("toolResultTokens ignores non-text content blocks"):
    val text = "1234" // 4 chars -> 1 token
    val result = CallToolResult(content = List(
      ContentBlock.Text(text),
      ContentBlock.Image("base64data", "image/png")
    ))
    assertEquals(CostHistogram.toolResultTokens(result), 1L)

  test("toolsListTokens sums tool name, description, and schema string length divided by 4"):
    val tool = Tool(
      name = "my_tool", // 7 chars
      description = Some("A test tool."), // 12 chars
      inputSchema = ObjectSchema.empty // "{}" -> 2 chars
    )
    val listResult = ListToolsResult(tools = List(tool), ttlMs = 60000L, cacheScope = CacheScope.`private`, nextCursor = None)
    val expectedChars = 7 + 12 + tool.inputSchema.toString.length
    assertEquals(CostHistogram.toolsListTokens(listResult), (expectedChars / 4).toLong)
