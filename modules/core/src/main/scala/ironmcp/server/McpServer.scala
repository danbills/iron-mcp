package ironmcp
package server

import cats.{Applicative, ApplicativeThrow}
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*

/** A stateless MCP server for revision 2026-07-28.
  *
  * It holds no per-client state at all: there is no handshake to remember, so
  * `handle` is a pure function of one message. That is what makes it safe to
  * run behind any number of replicas, and it is why this revision was chosen.
  *
  * Dispatch only lifts and maps provider results, so `F` needs nothing beyond
  * `Applicative`; the transport decides how much more it needs.
  */
final class McpServer[F[_]](
    val info: Implementation,
    val instructions: Option[NonEmptyString] = None,
    val tools: Option[ToolProvider[F]] = None,
    val resources: Option[ResourceProvider[F]] = None,
    val prompts: Option[PromptProvider[F]] = None,
    val completions: Option[CompletionProvider[F]] = None,
    val subscriptions: Option[SubscriptionProvider[F]] = None,
    val discoveryTtlMs: DiscoveryTtlMs = 300000L,
    val extensions: Option[Map[String, io.circe.JsonObject]] = None,
    val customHandlers: Map[String, Option[Json] => F[Either[JsonRpcError, Json]]] = Map.empty,
    val onUnhandled: Option[OnUnhandled[F]] = None
)(using F: Applicative[F]) extends ServerHandler[F]:

  /** Capabilities are derived from the providers, never declared separately —
    * a server cannot advertise tools it has no way to serve.
    */
  val capabilities: ServerCapabilities = ServerCapabilities(
    tools = tools.as(ToolsCapability(listChanged = Some(false))),
    resources = resources.as(ResourcesCapability(subscribe = subscriptions.as(true), listChanged = Some(false))),
    prompts = prompts.as(PromptsCapability(listChanged = Some(false))),
    completions = completions.as(io.circe.JsonObject.empty),
    extensions = extensions
  )

  private val serverMeta = Some(ResultMeta(serverInfo = Some(info)))

  /** Answers one message. `None` means the message was a notification, which by
    * JSON-RPC rule gets no reply.
    */
  def handle(message: JsonRpcMessage): F[Option[JsonRpcMessage]] = handleEmitting(message, _ => F.unit)

  /** [[handle]], with notifications the request emits going to `emit`. */
  override def handleEmitting(message: JsonRpcMessage, emit: NotificationSink[F]): F[Option[JsonRpcMessage]] = message match
    case JsonRpcMessage.Request(id, method, params) =>
      dispatch(Method.parse(method), params, emit).map {
        case Right(result) => Some(JsonRpcMessage.Success(id, result))
        case Left(error)   => Some(JsonRpcMessage.Failure(Some(id), error))
      }
    case JsonRpcMessage.Notification(_, _) => F.pure(None)
    // A stateless server never initiates a request, so it can never be the
    // recipient of a response.
    case JsonRpcMessage.Success(_, _) | JsonRpcMessage.Failure(_, _) => F.pure(None)

  /** [[handle]], for transports: a provider that fails becomes an error
    * response instead of failing the stream. An [[McpException]] carries the
    * error to send; anything else is `-32603` without its message, which may
    * hold internals the client should not see. That exception goes to
    * [[onUnhandled]], if set.
    */
  def handleRecovering(message: JsonRpcMessage)(using F: ApplicativeThrow[F]): F[Option[JsonRpcMessage]] =
    handleEmittingRecovering(message, _ => F.unit)

  override def handleEmittingRecovering(message: JsonRpcMessage, emit: NotificationSink[F])(using
      F: ApplicativeThrow[F]
  ): F[Option[JsonRpcMessage]] =
    OnUnhandled.recover(message, onUnhandled.getOrElse(OnUnhandled.noop[F]))(handleEmitting(message, emit))

  private def dispatch(method: Method, params: Option[Json], emit: NotificationSink[F]): F[Either[JsonRpcError, Json]] =
    method match
      case Method.Discover =>
        withParams[DiscoverParams](params) { request =>
          checkVersion(request._meta).traverse { _ =>
            F.pure(
              DiscoverResult(
                supportedVersions = List(LatestProtocolVersion),
                capabilities = capabilities,
                // The interval on DiscoveryTtlMs strictly implies the
                // non-negativity CacheTtlMs asks for, so this cannot fail.
                ttlMs = discoveryTtlMs.assume[CacheTtlMsC],
                cacheScope = CacheScope.`public`,
                instructions = instructions,
                _meta = serverMeta
              ).asJson
            )
          }
        }

      case Method.ToolsList =>
        served(tools, "tools") { provider =>
          withParams[ListToolsParams](params)(request => guarded(request._meta)(provider.list(request).map(_.asJson)))
        }

      case Method.ToolsCall =>
        served(tools, "tools") { provider =>
          withParams[CallToolParams](params) { request =>
            guarded(request._meta)(provider.callEmitting(request, Emit(request._meta, emit)).map(orInput[CallToolResult]))
          }
        }

      case Method.ResourcesList =>
        served(resources, "resources") { provider =>
          withParams[ListResourcesParams](params)(request => guarded(request._meta)(provider.list(request).map(_.asJson)))
        }

      case Method.ResourceTemplatesList =>
        served(resources, "resources") { provider =>
          withParams[ListResourcesParams](params) { request =>
            guarded(request._meta)(provider.templates(request).map(_.asJson))
          }
        }

      case Method.ResourcesRead =>
        served(resources, "resources") { provider =>
          withParams[ReadResourceParams](params)(request => guarded(request._meta)(provider.read(request).map(orInput[ReadResourceResult])))
        }

      case Method.PromptsList =>
        served(prompts, "prompts") { provider =>
          withParams[ListPromptsParams](params)(request => guarded(request._meta)(provider.list(request).map(_.asJson)))
        }

      case Method.PromptsGet =>
        served(prompts, "prompts") { provider =>
          withParams[GetPromptParams](params)(request => guarded(request._meta)(provider.get(request).map(orInput[GetPromptResult])))
        }

      case Method.CompletionComplete =>
        served(completions, "completions") { provider =>
          withParams[CompleteParams](params) { request =>
            guarded(request._meta)(provider.complete(request).map(_.asJson))
          }
        }

      case Method.SubscriptionsListen =>
        served(subscriptions, "subscriptions") { provider =>
          withParams[SubscriptionsListenParams](params) { request =>
            guarded(request._meta)(provider.listen(request).map(_.asJson))
          }
        }

      case Method.Unknown(raw) if customHandlers.contains(raw) =>
        customHandlers(raw)(params)

      case other =>
        F.pure(
          Left(JsonRpcError(ErrorCode.MethodNotFound, s"unsupported method: ${other.wire}".assumeNonEmpty))
        )

  /** Encodes the spec's `Result | InputRequiredResult` unions. `InputRequiredResult`
    * is a concrete class, so the match needs no TypeTest and no reflection.
    */
  private def orInput[A: Encoder](value: A | InputRequiredResult): Json =
    value match
      case input: InputRequiredResult => input.asJson
      case other                      => other.asInstanceOf[A].asJson

  private def served[P](provider: Option[P], capability: String)(
      run: P => F[Either[JsonRpcError, Json]]
  ): F[Either[JsonRpcError, Json]] =
    provider match
      case Some(value) => run(value)
      case None =>
        F.pure(
          Left(JsonRpcError(ErrorCode.MethodNotFound, s"this server does not serve $capability".assumeNonEmpty))
        )

  private def withParams[P: Decoder](params: Option[Json])(
      run: P => F[Either[JsonRpcError, Json]]
  ): F[Either[JsonRpcError, Json]] =
    params.getOrElse(Json.obj()).as[P] match
      case Right(decoded) => run(decoded)
      case Left(failure) =>
        F.pure(
          Left(JsonRpcError(ErrorCode.InvalidParams, failure.getMessage.assumeNonEmpty, Some(failure.getMessage.asJson)))
        )

  /** Every request carries the version envelope; a mismatch is `-32022` with
    * the supported list attached, exactly as the spec prescribes.
    */
  private def checkVersion(meta: RequestMeta): Either[JsonRpcError, Unit] =
    if meta.protocolVersion == (LatestProtocolVersion: String) then Right(())
    else
      Left(
        JsonRpcError(
          ErrorCode.UnsupportedProtocolVersion,
          "unsupported protocol version".assumeNonEmpty,
          Some(
            Json.obj(
              "supported" -> Json.arr(Json.fromString(LatestProtocolVersion)),
              "requested" -> Json.fromString(meta.protocolVersion)
            )
          )
        )
      )

  private def guarded(meta: RequestMeta)(run: => F[Json]): F[Either[JsonRpcError, Json]] =
    checkVersion(meta) match
      case Left(error) => F.pure(Left(error))
      case Right(_)    => run.map(Right(_))

/** Raised by a provider to answer with a specific JSON-RPC error, such as
  * `-32002` for a resource that does not exist. Tools should not raise it: a
  * tool's failure is an `isError` result the model can read.
  */
final case class McpException(error: JsonRpcError) extends RuntimeException(error.message: String)

object McpException:
  /** An unknown tool is the caller's mistake about the server, not a tool that
    * ran and failed, so it is a protocol error (`-32602`), as the spec says.
    */
  def unknownTool(name: String): McpException =
    McpException(JsonRpcError(ErrorCode.InvalidParams, s"Unknown tool: $name".assumeNonEmpty))

  def resourceNotFound(uri: String): McpException =
    McpException(JsonRpcError(ErrorCode.ResourceNotFound, "resource not found", Some(io.circe.Json.obj("uri" -> io.circe.Json.fromString(uri)))))

extension (self: String)
  private[server] def assumeNonEmpty: NonEmptyString =
    if self.isEmpty then "unspecified error" else self.asInstanceOf[NonEmptyString]

extension [A](self: Option[A])
  private[server] def as[B](value: => B): Option[B] = self.map(_ => value)
