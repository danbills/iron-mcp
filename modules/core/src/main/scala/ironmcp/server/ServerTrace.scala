package ironmcp
package server

import cats.{ApplicativeThrow, MonadThrow}
import cats.effect.{Clock, Ref, Sync}
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*


enum TraceStatus:
  case Ok
  case Error(code: Int, message: String)

object TraceStatus:
  given Encoder[TraceStatus] =
    case TraceStatus.Ok                => Json.obj("code" -> Json.fromString("ok"))
    case TraceStatus.Error(code, msg)  => Json.obj("code" -> Json.fromString("error"), "errorCode" -> Json.fromInt(code), "message" -> Json.fromString(msg))

final case class TraceSpan(
    traceId: String,
    spanId: String,
    parentSpanId: Option[String],
    name: String,
    method: String,
    target: Option[String],
    startEpochNs: Long,
    durationNs: Long,
    status: TraceStatus,
    attributes: Map[String, Json]
) derives Encoder.AsObject

final case class ServerTrace(
    serviceName: String,
    serviceVersion: String,
    spans: List[TraceSpan]
) derives Encoder.AsObject

enum TraceFormat:
  case Json, Perfetto, OtlpJson

  def mimeType: MimeType = "application/json"

object TraceFormat:
  def parse(raw: String): Either[String, TraceFormat] =
    raw.trim.toLowerCase match
      case "json"                          => Right(TraceFormat.Json)
      case "perfetto" | "chrome" | "chrome_trace" => Right(TraceFormat.Perfetto)
      case "otlp" | "otlp_json" | "otlp-json"     => Right(TraceFormat.OtlpJson)
      case other                           => Left(s"Unknown trace format '$other' (expected: json, perfetto, otlp_json)")

object TraceRenderer:

  def render(trace: ServerTrace, format: TraceFormat): String =
    renderJson(trace, format).noSpaces

  def renderJson(trace: ServerTrace, format: TraceFormat): Json =
    format match
      case TraceFormat.Json     => toJson(trace)
      case TraceFormat.Perfetto => toPerfetto(trace)
      case TraceFormat.OtlpJson => toOtlpJson(trace)

  private def toJson(trace: ServerTrace): Json =
    Json.obj(
      "serviceName"    -> Json.fromString(trace.serviceName),
      "serviceVersion" -> Json.fromString(trace.serviceVersion),
      "spanCount"      -> Json.fromInt(trace.spans.size),
      "spans"          -> trace.spans.asJson
    )

  /** Chrome Trace Event / Perfetto JSON format (`ui.perfetto.dev` / `chrome://tracing`). */
  private def toPerfetto(trace: ServerTrace): Json =
    val procMeta = Json.obj(
      "name" -> Json.fromString("process_name"),
      "ph"   -> Json.fromString("M"),
      "pid"  -> Json.fromInt(1),
      "args" -> Json.obj("name" -> Json.fromString(trace.serviceName))
    )
    val threadMeta = Json.obj(
      "name" -> Json.fromString("thread_name"),
      "ph"   -> Json.fromString("M"),
      "pid"  -> Json.fromInt(1),
      "tid"  -> Json.fromInt(1),
      "args" -> Json.obj("name" -> Json.fromString("mcp-server"))
    )
    val spanEvents = trace.spans.map { s =>
      val tsUs  = s.startEpochNs / 1000L
      val durUs = math.max(1L, s.durationNs / 1000L)
      val statusJson = s.status match
        case TraceStatus.Ok               => "ok"
        case TraceStatus.Error(code, msg) => s"error($code): $msg"
      val args = JsonObject.fromMap(
        s.attributes ++ Map(
          "trace_id" -> Json.fromString(s.traceId),
          "span_id"  -> Json.fromString(s.spanId),
          "status"   -> Json.fromString(statusJson)
        ) ++ s.parentSpanId.map(p => "parent_span_id" -> Json.fromString(p))
      )
      Json.obj(
        "name" -> Json.fromString(s.name),
        "cat"  -> Json.fromString("mcp"),
        "ph"   -> Json.fromString("X"),
        "ts"   -> Json.fromLong(tsUs),
        "dur"  -> Json.fromLong(durUs),
        "pid"  -> Json.fromInt(1),
        "tid"  -> Json.fromInt(1),
        "args" -> Json.fromJsonObject(args)
      )
    }
    Json.obj(
      "traceEvents"     -> Json.fromValues(procMeta :: threadMeta :: spanEvents),
      "displayTimeUnit" -> Json.fromString("ms"),
      "metadata" -> Json.obj(
        "service.name"    -> Json.fromString(trace.serviceName),
        "service.version" -> Json.fromString(trace.serviceVersion)
      )
    )

  /** OpenTelemetry OTLP/HTTP JSON (`ExportTraceServiceRequest`). */
  private def toOtlpJson(trace: ServerTrace): Json =
    def otlpAttr(k: String, v: Json): Json =
      val anyVal =
        v.asString.map(_ => Json.obj("stringValue" -> v))
          .orElse(v.asBoolean.map(_ => Json.obj("boolValue" -> v)))
          .orElse(v.asNumber.map(_.toLong.fold(Json.obj("doubleValue" -> v))(l => Json.obj("intValue" -> Json.fromString(l.toString)))))
          .getOrElse(Json.obj("stringValue" -> Json.fromString(v.noSpaces)))
      Json.obj("key" -> Json.fromString(k), "value" -> anyVal)

    val otlpSpans = trace.spans.map { s =>
      val endNs = s.startEpochNs + s.durationNs
      val statusObj = s.status match
        case TraceStatus.Ok               => Json.obj("code" -> Json.fromInt(1))
        case TraceStatus.Error(_, msg)    => Json.obj("code" -> Json.fromInt(2), "message" -> Json.fromString(msg))
      val base = JsonObject(
        "traceId"           -> Json.fromString(s.traceId),
        "spanId"            -> Json.fromString(s.spanId),
        "name"              -> Json.fromString(s.name),
        "kind"              -> Json.fromInt(2),
        "startTimeUnixNano" -> Json.fromString(s.startEpochNs.toString),
        "endTimeUnixNano"   -> Json.fromString(endNs.toString),
        "attributes"        -> Json.fromValues(s.attributes.toList.map((k, v) => otlpAttr(k, v))),
        "status"            -> statusObj
      )
      val withParent = s.parentSpanId.fold(base)(p => base.add("parentSpanId", Json.fromString(p)))
      Json.fromJsonObject(withParent)
    }
    Json.obj(
      "resourceSpans" -> Json.arr(
        Json.obj(
          "resource" -> Json.obj(
            "attributes" -> Json.arr(
              otlpAttr("service.name", Json.fromString(trace.serviceName)),
              otlpAttr("service.version", Json.fromString(trace.serviceVersion))
            )
          ),
          "scopeSpans" -> Json.arr(
            Json.obj(
              "scope" -> Json.obj("name" -> Json.fromString("iron-mcp"), "version" -> Json.fromString(trace.serviceVersion)),
              "spans" -> Json.fromValues(otlpSpans)
            )
          )
        )
      )
    )

final class TraceRecorder[F[_]] private (
    val serviceName: String,
    val serviceVersion: String,
    maxSpans: Int,
    ref: Ref[F, Vector[TraceSpan]]
)(using F: Sync[F]):

  def record(span: TraceSpan): F[Unit] =
    ref.update { cur =>
      val next = cur :+ span
      if next.size > maxSpans then next.drop(next.size - maxSpans) else next
    }

  def snapshot: F[ServerTrace] =
    ref.get.map(s => ServerTrace(serviceName, serviceVersion, s.toList))

  def render(format: TraceFormat): F[String] =
    snapshot.map(TraceRenderer.render(_, format))

  /** Wraps a [[ServerHandler]] to record every JSON-RPC request span and optionally
    * attach `_meta.tracing` (`traceId`, `spanId`, `durationMs`, `server`) on success responses.
    */
  def wrap(inner: ServerHandler[F], attachMeta: Boolean = true): ServerHandler[F] =
    val self = this
    new ServerHandler[F]:
      def handle(message: JsonRpcMessage): F[Option[JsonRpcMessage]] =
        traced(message)(inner.handle(message))
      def handleRecovering(message: JsonRpcMessage)(using ApplicativeThrow[F]): F[Option[JsonRpcMessage]] =
        traced(message)(inner.handleRecovering(message))
      override def handleEmitting(message: JsonRpcMessage, emit: NotificationSink[F]): F[Option[JsonRpcMessage]] =
        traced(message)(inner.handleEmitting(message, emit))
      override def handleEmittingRecovering(message: JsonRpcMessage, emit: NotificationSink[F])(using
          ApplicativeThrow[F]
      ): F[Option[JsonRpcMessage]] =
        traced(message)(inner.handleEmittingRecovering(message, emit))

      private def traced(message: JsonRpcMessage)(run: => F[Option[JsonRpcMessage]]): F[Option[JsonRpcMessage]] =
        message match
          case JsonRpcMessage.Request(_, method, params) =>
            val methodStr = method: String
            val targetOpt = methodStr match
              case "tools/call"     => params.flatMap(_.hcursor.get[String]("name").toOption)
              case "resources/read" => params.flatMap(_.hcursor.get[String]("uri").toOption)
              case "prompts/get"    => params.flatMap(_.hcursor.get[String]("name").toOption)
              case _                => None
            val spanName = targetOpt.fold(methodStr)(t => s"$methodStr $t")
            val (traceId, parentOpt) = TraceRecorder.extractParent(params)
            for
              spanId   <- F.delay(TraceRecorder.randomHex16())
              startEp  <- Clock[F].realTime.map(_.toNanos)
              startMon <- Clock[F].monotonic.map(_.toNanos)
              res      <- run.attempt
              endMon   <- Clock[F].monotonic.map(_.toNanos)
              durNs     = math.max(0L, endMon - startMon)
              span      = TraceRecorder.buildSpan(traceId, spanId, parentOpt, spanName, methodStr, targetOpt, startEp, durNs, res)
              _        <- self.record(span)
              out      <- res match
                            case Left(err) => F.raiseError(err)
                            case Right(opt) =>
                              F.pure(if attachMeta then opt.map(TraceRecorder.withTracingMeta(_, serviceName, traceId, spanId, durNs)) else opt)
            yield out
          case _ => run

  /** Exposes the server's live trace in Perfetto, OTLP JSON, and raw JSON as `trace://server/...` MCP resources. */
  def resources(using MonadThrow[F]): ResourceProvider[F] = new ResourceProvider[F]:
    private val defs: List[(Uri, NonEmptyString, NonEmptyString, TraceFormat)] = List(
      ("trace://server/perfetto.json": Uri, "Server Trace (Perfetto)" : NonEmptyString, "Chrome Trace Event / Perfetto JSON for ui.perfetto.dev" : NonEmptyString, TraceFormat.Perfetto),
      ("trace://server/otlp.json": Uri, "Server Trace (OTLP JSON)" : NonEmptyString, "OpenTelemetry ExportTraceServiceRequest JSON" : NonEmptyString, TraceFormat.OtlpJson),
      ("trace://server/trace.json": Uri, "Server Trace (JSON)" : NonEmptyString, "Structured JSON spans recorded by this MCP server" : NonEmptyString, TraceFormat.Json)
    )
    private val byUri = defs.map(d => (d._1: String) -> d._4).toMap

    def list(params: ListResourcesParams): F[ListResourcesResult] =
      F.pure(
        ListResourcesResult(
          defs.map((uri, name, desc, fmt) =>
            Resource(uri = uri, name = name, description = Some(desc), mimeType = Some(fmt.mimeType))
          ),
          0L,
          CacheScope.`private`
        )
      )

    def read(params: ReadResourceParams): F[ReadResourceResult | InputRequiredResult] =
      byUri.get(params.uri) match
        case Some(fmt) =>
          render(fmt).map(txt =>
            ReadResourceResult(List(ResourceContents.Text(params.uri, txt, Some(fmt.mimeType))), 0L, CacheScope.`private`)
          )
        case None => F.raiseError(McpException.resourceNotFound(params.uri))

object TraceRecorder:
  def inMemory[F[_]: Sync](
      serviceName: String,
      serviceVersion: String,
      maxSpans: Int = 500
  ): F[TraceRecorder[F]] =
    Ref.of[F, Vector[TraceSpan]](Vector.empty).map(new TraceRecorder[F](serviceName, serviceVersion, maxSpans, _))

  /** Span and trace ids need to be unlikely to collide, not unguessable: `scala.util.Random`, since Scala Native has
    * no `SecureRandom` (so no `UUID.randomUUID`).
    */
  private[server] def randomHex16(): String =
    val hex = java.lang.Long.toHexString(scala.util.Random.nextLong())
    "0" * (16 - hex.length) + hex

  private[server] def randomHex32(): String = randomHex16() + randomHex16()

  private[server] def extractParent(params: Option[Json]): (String, Option[String]) =
    val tp = params.flatMap(_.hcursor.downField("_meta").get[String]("traceparent").toOption)
    tp.flatMap { raw =>
      val parts = raw.trim.split("-")
      Option.when(parts.length == 4 && parts(1).length == 32 && parts(2).length == 16)((parts(1), Some(parts(2))))
    }.getOrElse((randomHex32(), None))

  private[server] def buildSpan(
      traceId: String,
      spanId: String,
      parentSpanId: Option[String],
      name: String,
      method: String,
      target: Option[String],
      startEpochNs: Long,
      durationNs: Long,
      res: Either[Throwable, Option[JsonRpcMessage]]
  ): TraceSpan =
    val baseAttrs = Map("mcp.method" -> Json.fromString(method)) ++ target.map(t => "mcp.target" -> Json.fromString(t))
    val (status, extra) = res match
      case Left(err) =>
        (TraceStatus.Error(-32603, Option(err.getMessage).getOrElse(err.getClass.getSimpleName)), Map.empty[String, Json])
      case Right(Some(JsonRpcMessage.Failure(_, err))) =>
        (TraceStatus.Error(err.code.code, err.message), Map.empty[String, Json])
      case Right(Some(JsonRpcMessage.Success(_, result))) =>
        val isErr = result.hcursor.get[Boolean]("isError").toOption
        val st    = if isErr.contains(true) then TraceStatus.Error(-32000, "tool returned isError=true") else TraceStatus.Ok
        val ex    = isErr.map(b => "mcp.is_error" -> Json.fromBoolean(b)).toMap
        (st, ex)
      case Right(_) =>
        (TraceStatus.Ok, Map.empty[String, Json])

    TraceSpan(traceId, spanId, parentSpanId, name, method, target, startEpochNs, durationNs, status, baseAttrs ++ extra)

  private[server] def withTracingMeta(
      msg: JsonRpcMessage,
      server: String,
      traceId: String,
      spanId: String,
      durationNs: Long
  ): JsonRpcMessage =
    msg match
      case JsonRpcMessage.Success(id, result) =>
        result.asObject match
          case Some(obj) =>
            val tracing = Json.obj(
              "traceId"    -> Json.fromString(traceId),
              "spanId"     -> Json.fromString(spanId),
              "durationMs" -> Json.fromDoubleOrNull(durationNs.toDouble / 1_000_000.0),
              "server"     -> Json.fromString(server)
            )
            val meta = obj("_meta").flatMap(_.asObject).getOrElse(JsonObject.empty).add("tracing", tracing)
            JsonRpcMessage.Success(id, Json.fromJsonObject(obj.add("_meta", Json.fromJsonObject(meta))))
          case None => msg
      case other => other
