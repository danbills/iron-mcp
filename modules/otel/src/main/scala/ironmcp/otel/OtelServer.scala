package ironmcp
package otel

import cats.ApplicativeThrow
import cats.effect.{Async, Clock}
import cats.syntax.all.*
import io.circe.Json
import ironmcp.protocol.*
import ironmcp.server.{McpServer, NotificationSink, ServerHandler}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.{SpanBuilder, SpanContext, SpanKind, StatusCode}

final class OtelServer[F[_]](
    val underlying: McpServer[F],
    val telemetry: McpTelemetry[F]
)(using F: Async[F]):

  def handle(
      message: JsonRpcMessage,
      transport: String = "unknown",
      httpContext: Option[SpanContext] = None,
      emit: NotificationSink[F] = (_: JsonRpcMessage.Notification) => F.unit
  ): F[Option[JsonRpcMessage]] =
    process(message, transport, httpContext, recovering = false, emit)

  def handleRecovering(
      message: JsonRpcMessage,
      transport: String = "unknown",
      httpContext: Option[SpanContext] = None,
      emit: NotificationSink[F] = (_: JsonRpcMessage.Notification) => F.unit
  ): F[Option[JsonRpcMessage]] =
    process(message, transport, httpContext, recovering = true, emit)

  /** This server as a [[ServerHandler]] for one transport, so the core
    * transports (`Stdio.pipe`, `McpHttp.answer`) can serve it, progress
    * notifications included.
    */
  def handlerFor(transport: String, httpContext: Option[SpanContext] = None): ServerHandler[F] =
    val self = this
    new ServerHandler[F]:
      def handle(message: JsonRpcMessage): F[Option[JsonRpcMessage]] = self.handle(message, transport, httpContext)
      def handleRecovering(message: JsonRpcMessage)(using ApplicativeThrow[F]): F[Option[JsonRpcMessage]] =
        self.handleRecovering(message, transport, httpContext)
      override def handleEmitting(message: JsonRpcMessage, emit: NotificationSink[F]): F[Option[JsonRpcMessage]] =
        self.handle(message, transport, httpContext, emit)
      override def handleEmittingRecovering(message: JsonRpcMessage, emit: NotificationSink[F])(using
          ApplicativeThrow[F]
      ): F[Option[JsonRpcMessage]] =
        self.handleRecovering(message, transport, httpContext, emit)

  private def process(
      message: JsonRpcMessage,
      transport: String,
      httpContext: Option[SpanContext],
      recovering: Boolean,
      emit: NotificationSink[F]
  ): F[Option[JsonRpcMessage]] =
    message match
      case req @ JsonRpcMessage.Request(id, method, params) =>
        traceRequest(req, id, method, params, transport, httpContext, recovering, emit)
      case other =>
        if recovering then underlying.handleEmittingRecovering(other, emit) else underlying.handleEmitting(other, emit)

  private def requestIdString(id: RequestId): String = id match
    case RequestId.Num(v)  => v.toString
    case RequestId.Text(v) => v: String

  private def traceRequest(
      req: JsonRpcMessage.Request,
      id: RequestId,
      method: NonEmptyString,
      params: Option[Json],
      transport: String,
      httpContext: Option[SpanContext],
      recovering: Boolean,
      emit: NotificationSink[F]
  ): F[Option[JsonRpcMessage]] =
    val methodStr = method: String
    val targetOpt: Option[String] = methodStr match
      case "tools/call"     => params.flatMap(_.hcursor.get[String]("name").toOption)
      case "resources/read" => params.flatMap(_.hcursor.get[String]("uri").toOption)
      case "prompts/get"    => params.flatMap(_.hcursor.get[String]("name").toOption)
      case _                => None

    val spanName = targetOpt.fold(methodStr)(t => s"$methodStr $t")
    val metaCtx  = TraceContext.fromMeta(params)
    val stitched = TraceContext.stitch(metaCtx, httpContext)

    val baseBuilder: SpanBuilder[F] = telemetry.tracer.spanBuilder(spanName).withSpanKind(SpanKind.Server)
    val parented: SpanBuilder[F]    = stitched.parent.fold(baseBuilder.root)(baseBuilder.withParent)
    val linked: SpanBuilder[F]      = stitched.links.foldLeft(parented)((b, l) => b.addLink(l))

    val version = params
      .flatMap(_.hcursor.downField("_meta").get[String](ReservedMeta.ProtocolVersion).toOption)
      .getOrElse(LatestProtocolVersion: String)

    val sessionOpt = params.flatMap(_.hcursor.downField("_meta").get[String]("sessionId").toOption)

    val initialAttrs = List(
      Attribute(McpSemantics.Attributes.MethodName, methodStr),
      Attribute(McpSemantics.Attributes.RequestId, requestIdString(id)),
      Attribute(McpSemantics.Attributes.NetworkTransport, transport),
      Attribute(McpSemantics.Attributes.ProtocolVersion, version)
    ) ++ sessionOpt.map(s => Attribute(McpSemantics.Attributes.SessionId, s)) ++
      (methodStr match
        case "tools/call" =>
          targetOpt.toList.flatMap(t =>
            List(
              Attribute(McpSemantics.Attributes.GenAiToolName, t),
              Attribute(McpSemantics.Attributes.GenAiOperationName, "execute_tool")
            )
          )
        case "resources/read" =>
          targetOpt.toList.map(u => Attribute(McpSemantics.Attributes.ResourceUri, u))
        case "prompts/get" =>
          targetOpt.toList.map(p => Attribute(McpSemantics.Attributes.GenAiPromptName, p))
        case _ => Nil
      )

    val configuredBuilder = linked.addAttributes(initialAttrs)

    configuredBuilder.build.use { span =>
      for
        start <- Clock[F].monotonic
        callResult <- (if recovering then underlying.handleEmittingRecovering(req, emit) else underlying.handleEmitting(req, emit)).attempt
        end   <- Clock[F].monotonic
        durationSec = (end - start).toNanos.toDouble / 1e9
        reply <- callResult match
                   case Left(ex) =>
                     val exName = ex.getClass.getName
                     val recordToolError =
                       if methodStr == "tools/call" then
                         val safeToolName = targetOpt
                           .filter(t => telemetry.knownTools.isEmpty || telemetry.knownTools.contains(t))
                           .getOrElse("unknown")
                         span.addAttribute(Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.ToolError.wire)) >>
                           telemetry.metrics.toolCalls.inc(
                             Seq(
                               Attribute(McpSemantics.Attributes.ToolName, safeToolName),
                               Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.ToolError.wire)
                             )
                           )
                       else F.unit

                     for
                       _ <- span.recordException(ex)
                       _ <- span.setStatus(StatusCode.Error, ex.getMessage)
                       _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorType, exName))
                       _ <- recordToolError
                       _ <- telemetry.metrics.serverOperationDuration.record(
                              durationSec,
                              initialAttrs :+ Attribute(McpSemantics.Attributes.ErrorType, exName)
                            )
                       res <- F.raiseError[Option[JsonRpcMessage]](ex)
                     yield res

                   case Right(Some(JsonRpcMessage.Failure(reqId, rpcError))) =>
                     val codeStr = rpcError.code.code.toString
                     val recordProtocol =
                       if methodStr == "tools/call" then
                         span.addAttribute(Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.Protocol.wire)) >>
                           telemetry.metrics.toolCalls.inc(
                             Seq(
                               Attribute(McpSemantics.Attributes.ToolName, "unknown"),
                               Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.Protocol.wire)
                             )
                           )
                       else F.unit

                     for
                       _ <- span.setStatus(StatusCode.Error, s"JSON-RPC error $codeStr: ${rpcError.message: String}")
                       _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorType, codeStr))
                       _ <- recordProtocol
                       _ <- telemetry.metrics.serverOperationDuration.record(
                              durationSec,
                              initialAttrs :+ Attribute(McpSemantics.Attributes.ErrorType, codeStr)
                            )
                     yield Some(JsonRpcMessage.Failure(reqId, rpcError))

                   case Right(Some(JsonRpcMessage.Success(reqId, resultJson))) =>
                     for
                       _ <- inspectSuccess(methodStr, targetOpt, resultJson, span)
                       _ <- telemetry.metrics.serverOperationDuration.record(durationSec, initialAttrs)
                     yield Some(JsonRpcMessage.Success(reqId, resultJson))

                   case Right(other) =>
                     for
                       _ <- span.setStatus(StatusCode.Ok)
                       _ <- telemetry.metrics.serverOperationDuration.record(durationSec, initialAttrs)
                     yield other
      yield reply
    }

  private def inspectSuccess(
      method: String,
      targetOpt: Option[String],
      resultJson: Json,
      span: org.typelevel.otel4s.trace.Span[F]
  ): F[Unit] =
    if method == "tools/call" then
      val isError = resultJson.hcursor.get[Boolean]("isError").getOrElse(false)
      val safeToolName = targetOpt
        .filter(t => telemetry.knownTools.isEmpty || telemetry.knownTools.contains(t))
        .getOrElse("unknown")

      if isError then
        for
          _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.ToolError.wire))
          _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorType, "tool_error"))
          _ <- span.setStatus(StatusCode.Error, "tool error")
          _ <- telemetry.metrics.toolCalls.inc(
                 Seq(
                   Attribute(McpSemantics.Attributes.ToolName, safeToolName),
                   Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.ToolError.wire)
                 )
               )
        yield ()
      else if resultJson.hcursor.get[String]("resultType").toOption.contains("input_required") then
        for
          _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.Ok.wire))
          _ <- span.setStatus(StatusCode.Ok)
          _ <- telemetry.metrics.toolCalls.inc(
                 Seq(
                   Attribute(McpSemantics.Attributes.ToolName, safeToolName),
                   Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.Ok.wire)
                 )
               )
        yield ()
      else
        resultJson.as[CallToolResult].toOption match
          case Some(callResult) =>
            val isDud  = targetOpt.exists(t => telemetry.dudDetector.isDud(t, callResult))
            val rung   = if isDud then ErrorRung.DudSuspect else ErrorRung.Ok
            val tokens = CostHistogram.toolResultTokens(callResult)
            for
              _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorRung, rung.wire))
              _ <- span.setStatus(StatusCode.Ok)
              _ <- telemetry.metrics.toolCalls.inc(
                     Seq(
                       Attribute(McpSemantics.Attributes.ToolName, safeToolName),
                       Attribute(McpSemantics.Attributes.ErrorRung, rung.wire)
                     )
                   )
              _ <- telemetry.metrics.toolResultTokens.record(
                     tokens,
                     Seq(Attribute(McpSemantics.Attributes.ToolName, safeToolName))
                   )
            yield ()
          case None =>
            for
              _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.Ok.wire))
              _ <- span.setStatus(StatusCode.Ok)
              _ <- telemetry.metrics.toolCalls.inc(
                     Seq(
                       Attribute(McpSemantics.Attributes.ToolName, safeToolName),
                       Attribute(McpSemantics.Attributes.ErrorRung, ErrorRung.Ok.wire)
                     )
                   )
            yield ()
    else if method == "tools/list" then
      for
        _ <- span.setStatus(StatusCode.Ok)
        _ <- resultJson.as[ListToolsResult].toOption.traverse_ { listResult =>
               val tokens = CostHistogram.toolsListTokens(listResult)
               telemetry.metrics.toolsListTokens.record(tokens)
             }
      yield ()
    else span.setStatus(StatusCode.Ok)

object OtelServer:

  /** Instruments an McpServer, automatically querying its registered tools if available. */
  def instrument[F[_]: Async](
      server: McpServer[F],
      telemetry: McpTelemetry[F]
  ): F[OtelServer[F]] =
    for
      known <- server.tools.fold(Async[F].pure(Set.empty[String])) { provider =>
                 provider
                   .list(
                     ListToolsParams(
                       RequestMeta(LatestProtocolVersion, ClientCapabilities(), None)
                     )
                   )
                   .map(_.tools.map(t => (t.name: String)).toSet)
                   .handleError(_ => Set.empty)
               }
      updatedTel = if telemetry.knownTools.isEmpty && known.nonEmpty then telemetry.withKnownTools(known) else telemetry
    yield new OtelServer(server, updatedTel)
