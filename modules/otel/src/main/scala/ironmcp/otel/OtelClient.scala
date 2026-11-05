package ironmcp
package otel

import cats.effect.{Async, Clock}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import ironmcp.client.ClientTransport
import ironmcp.protocol.{JsonRpcMessage, RequestId}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.{SpanContext, SpanKind, StatusCode}

object OtelClient:

  private def requestIdString(id: RequestId): String = id match
    case RequestId.Num(v)  => v.toString
    case RequestId.Text(v) => v: String

  /** Instruments a ClientTransport to trace requests and inject W3C traceparent into `_meta`. */
  def instrument[F[_]](
      transport: ClientTransport[F],
      telemetry: McpTelemetry[F],
      networkTransport: String = "unknown"
  )(using F: Async[F]): ClientTransport[F] = new ClientTransport[F]:

    def request(message: JsonRpcMessage.Request): F[JsonRpcMessage] =
      val methodStr = message.method: String
      val targetOpt: Option[String] = methodStr match
        case "tools/call"     => message.params.flatMap(_.hcursor.get[String]("name").toOption)
        case "resources/read" => message.params.flatMap(_.hcursor.get[String]("uri").toOption)
        case "prompts/get"    => message.params.flatMap(_.hcursor.get[String]("name").toOption)
        case _                => None

      val spanName = targetOpt.fold(methodStr)(t => s"$methodStr $t")
      val initialAttrs = List(
        Attribute(McpSemantics.Attributes.MethodName, methodStr),
        Attribute(McpSemantics.Attributes.RequestId, requestIdString(message.id)),
        Attribute(McpSemantics.Attributes.NetworkTransport, networkTransport)
      ) ++ (methodStr match
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

      telemetry.tracer
        .spanBuilder(spanName)
        .withSpanKind(SpanKind.Client)
        .addAttributes(initialAttrs)
        .build
        .use { span =>
          for
            start <- Clock[F].monotonic
            ctx = span.context
            modifiedMessage = injectMeta(message, ctx)
            replyAttempt <- transport.request(modifiedMessage).attempt
            end   <- Clock[F].monotonic
            durationSec = (end - start).toNanos.toDouble / 1e9
            reply <- replyAttempt match
                       case Left(ex) =>
                         val exName = ex.getClass.getName
                         for
                           _ <- span.recordException(ex)
                           _ <- span.setStatus(StatusCode.Error, ex.getMessage)
                           _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorType, exName))
                           _ <- telemetry.metrics.clientOperationDuration.record(
                                  durationSec,
                                  initialAttrs :+ Attribute(McpSemantics.Attributes.ErrorType, exName)
                                )
                           res <- F.raiseError[JsonRpcMessage](ex)
                         yield res
                       case Right(replyMsg) =>
                         replyMsg match
                           case JsonRpcMessage.Failure(_, error) =>
                             val codeStr = error.code.code.toString
                             for
                               _ <- span.setStatus(StatusCode.Error, s"JSON-RPC error $codeStr: ${error.message: String}")
                               _ <- span.addAttribute(Attribute(McpSemantics.Attributes.ErrorType, codeStr))
                               _ <- telemetry.metrics.clientOperationDuration.record(
                                      durationSec,
                                      initialAttrs :+ Attribute(McpSemantics.Attributes.ErrorType, codeStr)
                                    )
                             yield replyMsg
                           case _ =>
                             for
                               _ <- span.setStatus(StatusCode.Ok)
                               _ <- telemetry.metrics.clientOperationDuration.record(durationSec, initialAttrs)
                             yield replyMsg
          yield reply
        }

    def notify(message: JsonRpcMessage.Notification): F[Unit] =
      transport.notify(message)

    private def injectMeta(req: JsonRpcMessage.Request, ctx: SpanContext): JsonRpcMessage.Request =
      val existingParams = req.params.getOrElse(Json.obj())
      val existingMeta   = existingParams.hcursor.downField("_meta").as[JsonObject].getOrElse(JsonObject.empty)
      val updatedMeta    = TraceContext.injectMeta(ctx, existingMeta)
      val updatedParams =
        existingParams.asObject.getOrElse(JsonObject.empty).add("_meta", Json.fromJsonObject(updatedMeta))
      new JsonRpcMessage.Request(req.id, req.method, Some(Json.fromJsonObject(updatedParams)))
