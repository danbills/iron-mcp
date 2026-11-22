package ironmcp
package apps

import cats.effect.IO
import io.circe.Json
import ironmcp.server.*
import munit.CatsEffectSuite

class TraceAppSuite extends CatsEffectSuite:

  test("TraceApp builds TraceReport with Perfetto and OTLP JSON from TraceRecorder"):
    for
      rec <- TraceRecorder.inMemory[IO]("demo", "0.1.0")
      _   <- rec.record(TraceSpan("0".repeat(32), "1".repeat(16), None, "tools/call ping", "tools/call", Some("ping"), 1_000_000_000L, 5_000_000L, TraceStatus.Ok, Map("mcp.method" -> Json.fromString("tools/call"))))
      snap <- rec.snapshot
      rep   = TraceApp.buildReport(snap)
      tool  = TraceApp.tool(rec)
    yield
      assertEquals(rep.spanCount, 1)
      assertEquals(rep.errorCount, 0)
      assertEquals(rep.p50Ms, 5.0)
      assertEquals(tool.definition.name: String, "server_trace")
      assert(TraceApp.html.contains("Waterfall Timeline"))
