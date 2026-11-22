package ironmcp
package apps

import cats.Show
import cats.effect.Sync
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*

/** Interactive MCP App (`ui://iron-mcp/trace.html`) for inspecting server-side
  * [[TraceRecorder]] spans as a waterfall timeline, latency breakdown, and
  * exportable Perfetto / OTLP JSON / Raw JSON trace.
  */
object TraceApp:

  val ViewUri: UiUri = "ui://iron-mcp/trace.html"

  final case class TraceArgs(format: Option[NonEmptyString]) derives Decoder, JsonSchemaOf

  final case class MethodPerf(
      name: String,
      count: Int,
      errors: Int,
      avgMs: Double,
      p95Ms: Double,
      maxMs: Double
  ) derives Encoder.AsObject, JsonSchemaOf

  final case class TraceSpanView(
      traceId: String,
      spanId: String,
      parentSpanId: Option[String],
      name: String,
      method: String,
      target: Option[String],
      startEpochNs: Long,
      durationNs: Long,
      durationMs: Double,
      status: String,
      errorMessage: Option[String],
      attributes: Map[String, Json]
  ) derives Encoder.AsObject, JsonSchemaOf

  final case class TraceReport(
      serviceName: String,
      serviceVersion: String,
      spanCount: Int,
      errorCount: Int,
      p50Ms: Double,
      p95Ms: Double,
      maxMs: Double,
      spans: List[TraceSpanView],
      byOperation: List[MethodPerf],
      perfetto: Json,
      otlpJson: Json
  ) derives Encoder.AsObject, JsonSchemaOf

  object TraceReport:
    given Show[TraceReport] = r =>
      f"${r.serviceName} v${r.serviceVersion} trace: ${r.spanCount} spans (${r.errorCount} errors), p50=${r.p50Ms}%.2fms, p95=${r.p95Ms}%.2fms, max=${r.maxMs}%.2fms"

  private def percentile(sorted: List[Double], p: Double): Double =
    if sorted.isEmpty then 0.0
    else
      val idx = math.min(sorted.size - 1, math.max(0, math.ceil(p * sorted.size).toInt - 1))
      sorted(idx)

  def buildReport(trace: ServerTrace): TraceReport =
    val views = trace.spans.map { s =>
      val (st, msg) = s.status match
        case TraceStatus.Ok            => ("ok", None)
        case TraceStatus.Error(_, err) => ("error", Some(err))
      TraceSpanView(
        traceId      = s.traceId,
        spanId       = s.spanId,
        parentSpanId = s.parentSpanId,
        name         = s.name,
        method       = s.method,
        target       = s.target,
        startEpochNs = s.startEpochNs,
        durationNs   = s.durationNs,
        durationMs   = s.durationNs.toDouble / 1_000_000.0,
        status       = st,
        errorMessage = msg,
        attributes   = s.attributes
      )
    }
    val durs   = views.map(_.durationMs).sorted
    val errCnt = views.count(_.status == "error")
    val byOp = views.groupBy(_.name).toList.map { (op, list) =>
      val ds = list.map(_.durationMs).sorted
      MethodPerf(
        name   = op,
        count  = list.size,
        errors = list.count(_.status == "error"),
        avgMs  = ds.sum / list.size,
        p95Ms  = percentile(ds, 0.95),
        maxMs  = ds.lastOption.getOrElse(0.0)
      )
    }.sortBy(-_.maxMs)

    TraceReport(
      serviceName    = trace.serviceName,
      serviceVersion = trace.serviceVersion,
      spanCount      = views.size,
      errorCount     = errCnt,
      p50Ms          = percentile(durs, 0.50),
      p95Ms          = percentile(durs, 0.95),
      maxMs          = durs.lastOption.getOrElse(0.0),
      spans          = views,
      byOperation    = byOp,
      perfetto       = TraceRenderer.renderJson(trace, TraceFormat.Perfetto),
      otlpJson       = TraceRenderer.renderJson(trace, TraceFormat.OtlpJson)
    )

  def tool[F[_]: Sync](
      recorder: TraceRecorder[F],
      name: ToolName = "server_trace"
  ): McpTool[F] =
    McpTool[TraceArgs](
      name        = name,
      description = "Opens an interactive MCP App trace & performance viewer for this MCP server (waterfall timeline, P50/P95/max latencies, DAG step spans, and Perfetto / OTLP JSON / Raw JSON export).",
      title       = Some("MCP Server Trace Viewer"),
      annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(false)))
    )(_ => recorder.snapshot.map(buildReport)).withUi(ViewUri)

  lazy val resource: UiResource = UiResource(
    uri         = ViewUri,
    name        = "MCP Server Trace Viewer",
    html        = html,
    description = Some("Interactive waterfall timeline, latency breakdown, and Perfetto/OTLP exporter for MCP server traces."),
    meta        = UiResourceMeta(prefersBorder = Some(true))
  )

  val html: String =
    """<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |<meta charset="utf-8" />
      |<meta name="viewport" content="width=device-width, initial-scale=1" />
      |<title>MCP Server Trace &amp; Perf Viewer</title>
      |<style>
      |  :root {
      |    --bg: #0b0f17;
      |    --surface: rgba(17, 24, 39, 0.9);
      |    --surface-2: rgba(30, 41, 59, 0.72);
      |    --border: rgba(148, 163, 184, 0.16);
      |    --text: #f1f5f9;
      |    --muted: #94a3b8;
      |    --accent: #38bdf8;
      |    --indigo: #818cf8;
      |    --good: #34d399;
      |    --warn: #fbbf24;
      |    --bad: #f87171;
      |    --shadow: 0 12px 32px rgba(0, 0, 0, 0.35);
      |  }
      |  @media (prefers-color-scheme: light) {
      |    :root:not([data-theme="dark"]) {
      |      --bg: #f8fafc;
      |      --surface: rgba(255, 255, 255, 0.95);
      |      --surface-2: rgba(241, 245, 249, 0.92);
      |      --border: rgba(15, 23, 42, 0.1);
      |      --text: #0f172a;
      |      --muted: #64748b;
      |      --accent: #0284c7;
      |      --indigo: #4f46e5;
      |      --good: #059669;
      |      --warn: #d97706;
      |      --bad: #dc2626;
      |      --shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
      |    }
      |  }
      |  * { box-sizing: border-box; }
      |  body {
      |    margin: 0; padding: 16px; font-family: ui-sans-serif, system-ui, -apple-system, sans-serif;
      |    background: radial-gradient(900px 400px at 10% -10%, rgba(56, 189, 248, 0.14), transparent 60%), var(--bg);
      |    color: var(--text); line-height: 1.45;
      |  }
      |  .wrap { max-width: 1160px; margin: 0 auto; display: flex; flex-direction: column; gap: 12px; }
      |  .topbar {
      |    display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px;
      |    padding: 14px 16px; border-radius: 14px; background: var(--surface); border: 1px solid var(--border); box-shadow: var(--shadow);
      |  }
      |  h1 { margin: 0; font-size: 16px; font-weight: 700; }
      |  .sub { font-size: 12px; color: var(--muted); }
      |  .btns { display: flex; gap: 6px; flex-wrap: wrap; }
      |  .btn {
      |    appearance: none; border: 1px solid var(--border); background: var(--surface-2); color: var(--text);
      |    padding: 6px 11px; border-radius: 999px; font-size: 12px; font-weight: 600; cursor: pointer;
      |  }
      |  .btn:hover { border-color: var(--accent); }
      |  .btn.primary { background: var(--accent); color: #fff; border-color: transparent; }
      |  .kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
      |  @media (max-width: 720px) { .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
      |  .card { background: var(--surface); border: 1px solid var(--border); border-radius: 14px; padding: 14px; box-shadow: var(--shadow); }
      |  .kpi-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--muted); font-weight: 700; }
      |  .kpi-val { font-size: 24px; font-weight: 750; margin-top: 4px; font-variant-numeric: tabular-nums; }
      |  .section-title { font-size: 11.5px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.07em; color: var(--muted); margin-bottom: 10px; }
      |  .wf-list { display: flex; flex-direction: column; gap: 6px; max-height: 380px; overflow-y: auto; }
      |  .wf-row {
      |    display: grid; grid-template-columns: 260px 1fr 78px; align-items: center; gap: 10px;
      |    padding: 6px 8px; border-radius: 8px; background: var(--surface-2); border: 1px solid transparent; cursor: pointer;
      |  }
      |  .wf-row:hover, .wf-row.selected { border-color: var(--accent); }
      |  .wf-name { font-size: 12px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      |  .wf-track { position: relative; height: 14px; border-radius: 4px; background: rgba(148, 163, 184, 0.12); overflow: hidden; }
      |  .wf-bar { position: absolute; top: 2px; bottom: 2px; border-radius: 3px; min-width: 3px; background: var(--accent); }
      |  .wf-bar.err { background: var(--bad); }
      |  .wf-bar.dag { background: var(--indigo); }
      |  .wf-dur { text-align: right; font-family: ui-monospace, monospace; font-size: 11.5px; color: var(--muted); }
      |  .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
      |  @media (max-width: 780px) { .two-col { grid-template-columns: 1fr; } }
      |  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11.5px; }
      |  pre { margin: 0; padding: 10px; border-radius: 10px; background: var(--surface-2); border: 1px solid var(--border); overflow-x: auto; max-height: 240px; font-size: 11px; }
      |  .op-row { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid var(--border); font-size: 12px; }
      |  .op-row:last-child { border-bottom: none; }
      |</style>
      |</head>
      |<body>
      |<div class="wrap">
      |  <div class="topbar">
      |    <div>
      |      <h1 id="title">MCP Server Trace &amp; Perf Viewer</h1>
      |      <div class="sub" id="subtitle">Waiting for trace snapshot…</div>
      |    </div>
      |    <div class="btns">
      |      <button class="btn" id="dlPerfetto">⬇ Perfetto JSON</button>
      |      <button class="btn" id="dlOtlp">⬇ OTLP JSON</button>
      |      <button class="btn" id="cpPerfetto">Copy Perfetto</button>
      |      <button class="btn primary" id="refreshBtn">↻ Refresh</button>
      |    </div>
      |  </div>
      |  <div class="kpis" id="kpis"></div>
      |  <div class="card">
      |    <div class="section-title">Waterfall Timeline (click a span to inspect)</div>
      |    <div class="wf-list" id="waterfall"></div>
      |  </div>
      |  <div class="two-col">
      |    <div class="card">
      |      <div class="section-title">Operation Latency Breakdown</div>
      |      <div id="ops"></div>
      |    </div>
      |    <div class="card">
      |      <div class="section-title">Span Inspector</div>
      |      <pre id="inspector">Select a span above to inspect traceId, spanId, parentSpanId, and attributes.</pre>
      |    </div>
      |  </div>
      |</div>
      |<script>
      |(function () {
      |  const host = window.parent;
      |  let nextId = 1;
      |  let latest = null;
      |  let toolName = "server_trace";
      |  const pending = new Map();
      |
      |  function rpc(method, params) {
      |    const id = nextId++;
      |    return new Promise((resolve, reject) => {
      |      pending.set(id, { resolve, reject });
      |      host.postMessage({ jsonrpc: "2.0", id, method, params }, "*");
      |    });
      |  }
      |  function esc(s) {
      |    return String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
      |  }
      |  function downloadJson(name, obj) {
      |    const blob = new Blob([JSON.stringify(obj, null, 2)], { type: "application/json" });
      |    const a = document.createElement("a");
      |    a.href = URL.createObjectURL(blob);
      |    a.download = name;
      |    a.click();
      |  }
      |  function render(d) {
      |    if (!d) return;
      |    latest = d;
      |    document.getElementById("title").textContent = (d.serviceName || "MCP Server") + " — Trace & Perf";
      |    document.getElementById("subtitle").textContent = `v${d.serviceVersion || "?"} · ${d.spanCount || 0} spans recorded`;
      |    const errColor = d.errorCount > 0 ? "var(--bad)" : "var(--good)";
      |    document.getElementById("kpis").innerHTML = [
      |      `<div class="card"><div class="kpi-label">Spans</div><div class="kpi-val">${d.spanCount || 0}</div></div>`,
      |      `<div class="card"><div class="kpi-label">P50 Latency</div><div class="kpi-val">${Number(d.p50Ms || 0).toFixed(2)}ms</div></div>`,
      |      `<div class="card"><div class="kpi-label">P95 / Max</div><div class="kpi-val">${Number(d.p95Ms || 0).toFixed(1)} / ${Number(d.maxMs || 0).toFixed(1)}ms</div></div>`,
      |      `<div class="card"><div class="kpi-label">Errors</div><div class="kpi-val" style="color:${errColor}">${d.errorCount || 0}</div></div>`
      |    ].join("");
      |
      |    const spans = d.spans || [];
      |    const minNs = spans.length ? Math.min(...spans.map(s => s.startEpochNs)) : 0;
      |    const maxNs = spans.length ? Math.max(...spans.map(s => s.startEpochNs + s.durationNs)) : 1;
      |    const totalNs = Math.max(1, maxNs - minNs);
      |    const byId = new Set(spans.map(s => s.spanId));
      |
      |    document.getElementById("waterfall").innerHTML = spans.map((s, idx) => {
      |      const left = ((s.startEpochNs - minNs) / totalNs) * 100;
      |      const width = Math.max(0.8, (s.durationNs / totalNs) * 100);
      |      const indent = s.parentSpanId && byId.has(s.parentSpanId) ? "padding-left:18px;" : "";
      |      const cls = s.status === "error" ? "err" : (s.method && s.method.startsWith("dags") ? "dag" : "");
      |      return `<div class="wf-row" data-idx="${idx}" style="${indent}"><div class="wf-name" title="${esc(s.name)}">${esc(s.name)}</div><div class="wf-track"><div class="wf-bar ${cls}" style="left:${left.toFixed(2)}%;width:${width.toFixed(2)}%"></div></div><div class="wf-dur">${Number(s.durationMs).toFixed(2)}ms</div></div>`;
      |    }).join("") || `<div class="sub">No spans recorded yet.</div>`;
      |
      |    document.querySelectorAll(".wf-row").forEach(el => {
      |      el.addEventListener("click", () => {
      |        document.querySelectorAll(".wf-row").forEach(r => r.classList.remove("selected"));
      |        el.classList.add("selected");
      |        const s = spans[Number(el.dataset.idx)];
      |        document.getElementById("inspector").textContent = JSON.stringify(s, null, 2);
      |      });
      |    });
      |
      |    document.getElementById("ops").innerHTML = (d.byOperation || []).map(o =>
      |      `<div class="op-row"><span class="mono">${esc(o.name)}</span><span class="mono">${o.count}x · avg ${Number(o.avgMs).toFixed(1)}ms · p95 ${Number(o.p95Ms).toFixed(1)}ms${o.errors ? " · " + o.errors + " err" : ""}</span></div>`
      |    ).join("") || `<div class="sub">No operations recorded.</div>`;
      |
      |    reportSize();
      |  }
      |  function reportSize() {
      |    host.postMessage({ jsonrpc: "2.0", method: "ui/notifications/size-changed", params: { height: document.documentElement.scrollHeight } }, "*");
      |  }
      |  document.getElementById("dlPerfetto").addEventListener("click", () => latest && downloadJson("mcp-trace-perfetto.json", latest.perfetto));
      |  document.getElementById("dlOtlp").addEventListener("click", () => latest && downloadJson("mcp-trace-otlp.json", latest.otlpJson));
      |  document.getElementById("cpPerfetto").addEventListener("click", () => latest && navigator.clipboard && navigator.clipboard.writeText(JSON.stringify(latest.perfetto)));
      |  document.getElementById("refreshBtn").addEventListener("click", async () => {
      |    const res = await rpc("tools/call", { name: toolName, arguments: {} });
      |    if (res && res.structuredContent) render(res.structuredContent);
      |  });
      |  window.addEventListener("message", (e) => {
      |    const m = e.data;
      |    if (!m || m.jsonrpc !== "2.0") return;
      |    if (m.id !== undefined && pending.has(m.id)) {
      |      const { resolve, reject } = pending.get(m.id);
      |      pending.delete(m.id);
      |      m.error ? reject(new Error(m.error.message || "RPC error")) : resolve(m.result);
      |      return;
      |    }
      |    if (m.method === "ui/notifications/tool-input" && m.params && m.params.toolName) toolName = m.params.toolName;
      |    if (m.method === "ui/notifications/tool-result" && m.params && m.params.structuredContent) render(m.params.structuredContent);
      |    if (m.method === "ui/notifications/host-context-changed" && m.params && m.params.theme) document.documentElement.setAttribute("data-theme", m.params.theme);
      |    if (m.method === "ui/resource-teardown" && m.id !== undefined) host.postMessage({ jsonrpc: "2.0", id: m.id, result: {} }, "*");
      |  });
      |  new ResizeObserver(reportSize).observe(document.body);
      |  rpc("ui/initialize", { appInfo: { name: "iron-mcp-trace-viewer", version: "0.1.0" }, appCapabilities: {}, protocolVersion: "2026-01-26" })
      |    .then(() => { host.postMessage({ jsonrpc: "2.0", method: "ui/notifications/initialized", params: {} }, "*"); reportSize(); })
      |    .catch(() => {});
      |})();
      |</script>
      |</body>
      |</html>
      |""".stripMargin
