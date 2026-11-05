package ironmcp
package otel

import io.circe.{Json, JsonObject}
import org.http4s.Headers
import org.typelevel.ci.CIString
import org.typelevel.otel4s.trace.{SpanContext, TraceFlags, TraceState}

object TraceContext:

  val TraceParentHeader: CIString = CIString("traceparent")
  val TraceStateHeader: CIString  = CIString("tracestate")

  val TraceParentField: String = "traceparent"
  val TraceStateField: String  = "tracestate"

  final case class StitchResult(parent: Option[SpanContext], links: List[SpanContext])

  /** Precedence rule from MCP Eng Handbook Section 10.2.3:
    * - If meta context exists, it is the parent of the MCP span.
    * - If HTTP context also exists and differs from meta, HTTP trace is attached as a span link.
    * - If only HTTP exists, HTTP is the parent.
    * - If neither exists, no remote parent (starts a new root).
    */
  def stitch(meta: Option[SpanContext], http: Option[SpanContext]): StitchResult =
    (meta, http) match
      case (Some(m), Some(h)) =>
        if m == h then StitchResult(Some(m), Nil)
        else StitchResult(Some(m), List(h))
      case (Some(m), None) => StitchResult(Some(m), Nil)
      case (None, Some(h)) => StitchResult(Some(h), Nil)
      case (None, None)    => StitchResult(None, Nil)

  /** Extracts W3C traceparent and optional tracestate from JSON-RPC params._meta */
  def fromMeta(params: Option[Json]): Option[SpanContext] =
    for
      p <- params
      cursor = p.hcursor.downField("_meta")
      parentStr <- cursor.get[String](TraceParentField).toOption
      stateStr = cursor.get[String](TraceStateField).toOption
      ctx <- parseTraceParent(parentStr, stateStr)
    yield ctx

  /** Extracts W3C traceparent and optional tracestate from http4s Headers */
  def fromHeaders(headers: Headers): Option[SpanContext] =
    headers.get(TraceParentHeader).map(_.head.value).flatMap { parentStr =>
      val stateStr = headers.get(TraceStateHeader).map(_.head.value)
      parseTraceParent(parentStr, stateStr)
    }

  /** Parses a W3C traceparent header: 00-{traceId}-{spanId}-{flags} */
  def parseTraceParent(header: String, stateStr: Option[String] = None): Option[SpanContext] =
    val trimmed = header.trim
    val parts   = trimmed.split("-")
    if parts.length == 4 && parts(0) == "00" && parts(1).length == 32 && parts(2).length == 16 && parts(3).length == 2 then
      for
        traceId <- SpanContext.TraceId.fromHex(parts(1))
        spanId  <- SpanContext.SpanId.fromHex(parts(2))
        flags   <- TraceFlags.fromHex(parts(3))
        if SpanContext.TraceId.isValid(traceId) && SpanContext.SpanId.isValid(spanId)
      yield
        val state = stateStr.flatMap(parseTraceState).getOrElse(TraceState.empty)
        SpanContext(traceId, spanId, flags, state, remote = true)
    else None

  def parseTraceState(header: String): Option[TraceState] =
    val pairs = header.split(",").iterator.map(_.trim).filter(_.nonEmpty).flatMap { pair =>
      val idx = pair.indexOf('=')
      if idx > 0 then Some(pair.substring(0, idx).trim -> pair.substring(idx + 1).trim)
      else None
    }.toList
    if pairs.isEmpty then None
    else
      val state = pairs.foldLeft(TraceState.empty) { case (acc, (k, v)) => acc.updated(k, v) }
      Some(state)

  /** Formats a SpanContext into a W3C traceparent string */
  def renderTraceParent(ctx: SpanContext): String =
    s"00-${ctx.traceIdHex}-${ctx.spanIdHex}-${ctx.traceFlags.toHex}"

  /** Formats a SpanContext's TraceState into a W3C tracestate string */
  def renderTraceState(ctx: SpanContext): Option[String] =
    val map = ctx.traceState.asMap
    if map.isEmpty then None
    else Some(map.iterator.map((k, v) => s"$k=$v").mkString(","))

  /** Injects traceparent and optional tracestate into an existing JsonObject representing params._meta */
  def injectMeta(ctx: SpanContext, meta: JsonObject): JsonObject =
    val withParent = meta.add(TraceParentField, Json.fromString(renderTraceParent(ctx)))
    renderTraceState(ctx) match
      case Some(s) => withParent.add(TraceStateField, Json.fromString(s))
      case None    => withParent
