package ironmcp
package otel

import io.circe.Json
import munit.FunSuite
import org.http4s.{Header, Headers}
import org.typelevel.ci.CIString
import org.typelevel.otel4s.trace.{SpanContext, TraceFlags}
import scodec.bits.ByteVector

class TraceContextSuite extends FunSuite:

  private val validTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val validSpanId  = "00f067aa0ba902b7"
  private val validHeader  = s"00-$validTraceId-$validSpanId-01"

  test("parses a valid W3C traceparent header"):
    val ctxOpt = TraceContext.parseTraceParent(validHeader)
    assert(ctxOpt.isDefined)
    val ctx = ctxOpt.get
    assertEquals(ctx.traceIdHex, validTraceId)
    assertEquals(ctx.spanIdHex, validSpanId)
    assertEquals(ctx.traceFlags.toHex, "01")
    assert(ctx.isSampled)
    assert(ctx.isRemote)

  test("rejects invalid traceparent formats"):
    assertEquals(TraceContext.parseTraceParent(""), None)
    assertEquals(TraceContext.parseTraceParent("not-enough-parts"), None)
    assertEquals(TraceContext.parseTraceParent("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"), None) // unsupported version
    assertEquals(TraceContext.parseTraceParent("00-invalidtraceid-00f067aa0ba902b7-01"), None)
    assertEquals(TraceContext.parseTraceParent(s"00-00000000000000000000000000000000-$validSpanId-01"), None) // all zeros traceId invalid
    assertEquals(TraceContext.parseTraceParent(s"00-$validTraceId-0000000000000000-01"), None) // all zeros spanId invalid

  test("parses and renders tracestate"):
    val stateHeader = "rojo=1,congo=2"
    val state = TraceContext.parseTraceState(stateHeader)
    assert(state.isDefined)
    assertEquals(state.get.get("rojo"), Some("1"))
    assertEquals(state.get.get("congo"), Some("2"))

    val ctx = SpanContext(
      ByteVector.fromValidHex(validTraceId),
      ByteVector.fromValidHex(validSpanId),
      TraceFlags.Sampled,
      state.get,
      remote = true
    )
    val renderedParent = TraceContext.renderTraceParent(ctx)
    assertEquals(renderedParent, validHeader)
    val renderedState = TraceContext.renderTraceState(ctx)
    assert(renderedState.isDefined)
    assert(renderedState.get.contains("rojo=1"))
    assert(renderedState.get.contains("congo=2"))

  test("extracts from params._meta"):
    val json = Json.obj(
      "_meta" -> Json.obj(
        "traceparent" -> Json.fromString(validHeader),
        "tracestate"  -> Json.fromString("vendor=data")
      )
    )
    val extracted = TraceContext.fromMeta(Some(json))
    assert(extracted.isDefined)
    assertEquals(extracted.get.traceIdHex, validTraceId)
    assertEquals(extracted.get.traceState.get("vendor"), Some("data"))

  test("injects into params._meta"):
    val ctx = TraceContext.parseTraceParent(validHeader, Some("k=v")).get
    val meta = io.circe.JsonObject.empty
    val injected = TraceContext.injectMeta(ctx, meta)
    assertEquals(injected(TraceContext.TraceParentField).flatMap(_.asString), Some(validHeader))
    assertEquals(injected(TraceContext.TraceStateField).flatMap(_.asString), Some("k=v"))

  test("extracts from HTTP headers"):
    val headers = Headers(
      Header.Raw(CIString("traceparent"), validHeader),
      Header.Raw(CIString("tracestate"), "k=v")
    )
    val extracted = TraceContext.fromHeaders(headers)
    assert(extracted.isDefined)
    assertEquals(extracted.get.traceIdHex, validTraceId)
    assertEquals(extracted.get.traceState.get("k"), Some("v"))

  test("Section 10.2.3 precedence table: meta wins and HTTP is linked when different"):
    val metaTraceId = "11111111111111111111111111111111"
    val httpTraceId = "22222222222222222222222222222222"
    val metaCtx = TraceContext.parseTraceParent(s"00-$metaTraceId-$validSpanId-01")
    val httpCtx = TraceContext.parseTraceParent(s"00-$httpTraceId-$validSpanId-01")

    // Both present and different: parent is meta, HTTP is link
    val stitchedDiff = TraceContext.stitch(metaCtx, httpCtx)
    assertEquals(stitchedDiff.parent, metaCtx)
    assertEquals(stitchedDiff.links, List(httpCtx.get))

    // Both present and same: parent is meta, no link
    val stitchedSame = TraceContext.stitch(metaCtx, metaCtx)
    assertEquals(stitchedSame.parent, metaCtx)
    assertEquals(stitchedSame.links, Nil)

    // Meta only: parent is meta, no link
    val stitchedMetaOnly = TraceContext.stitch(metaCtx, None)
    assertEquals(stitchedMetaOnly.parent, metaCtx)
    assertEquals(stitchedMetaOnly.links, Nil)

    // HTTP only: parent is HTTP, no link
    val stitchedHttpOnly = TraceContext.stitch(None, httpCtx)
    assertEquals(stitchedHttpOnly.parent, httpCtx)
    assertEquals(stitchedHttpOnly.links, Nil)

    // Neither: no parent, no link
    val stitchedNone = TraceContext.stitch(None, None)
    assertEquals(stitchedNone.parent, None)
    assertEquals(stitchedNone.links, Nil)
