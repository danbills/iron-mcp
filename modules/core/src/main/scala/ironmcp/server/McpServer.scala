package ironmcp
package server

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*

/** A stateless MCP server for revision 2026-07-28.
  *
  * It holds no per-client state at all: there is no handshake to remember, so
  * `handle` is a pure function of one message. That is what makes it safe to
  * run behind any number of replicas, and it is why this revision was chosen.
  */
final class McpServer(
    val info: Implementation,
    val instructions: Option[NonEmptyString] = None,
    val tools: Option[ToolProvider] = None,
    val resources: Option[ResourceProvider] = None,
    val prompts: Option[PromptProvider] = None,
    val completions: Option[CompletionProvider] = None,
    val subscriptions: Option[SubscriptionProvider] = None,
    val discoveryTtlMs: Long = 300000L
):

  /** Capabilities are derived from the providers, never declared separately —
    * a server cannot advertise tools it has no way to serve.
    */
  val capabilities: ServerCapabilities = ServerCapabilities(
    tools = tools.as(ToolsCapability(listChanged = Some(false))),
    resources = resources.as(ResourcesCapability(subscribe = subscriptions.as(true), listChanged = Some(false))),
    prompts = prompts.as(PromptsCapability(listChanged = Some(false))),
    completions = completions.as(io.circe.JsonObject.empty)
  )

  private val serverMeta = Some(ResultMeta(serverInfo = Some(info)))

  /** Answers one message. `None` means the message was a notification, which by
    * JSON-RPC rule gets no reply.
    */
  def handle(message: JsonRpcMessage): IO[Option[JsonRpcMessage]] = message match
    case JsonRpcMessage.Request(id, method, params) =>
      dispatch(Method.parse(method), params).map {
        case Right(result) => Some(JsonRpcMessage.Success(id, result))
        case Left(error)   => Some(JsonRpcMessage.Failure(Some(id), error))
      }
    case JsonRpcMessage.Notification(_, _) => IO.none
    // A stateless server never initiates a request, so it can never be the
    // recipient of a response.
    case JsonRpcMessage.Success(_, _) | JsonRpcMessage.Failure(_, _) => IO.none

  private def dispatch(method: Method, params: Option[Json]): IO[Either[JsonRpcError, Json]] =
    method match
      case Method.Discover =>
        withParams[DiscoverParams](params) { request =>
          checkVersion(request._meta).traverse { _ =>
            IO.pure(
              DiscoverResult(
                supportedVersions = List(LatestProtocolVersion),
                capabilities = capabilities,
                ttlMs = discoveryTtlMs,
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
            guarded(request._meta)(provider.call(request).map(orInput[CallToolResult]))
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

      case other =>
        IO.pure(
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
      run: P => IO[Either[JsonRpcError, Json]]
  ): IO[Either[JsonRpcError, Json]] =
    provider match
      case Some(value) => run(value)
      case None =>
        IO.pure(
          Left(JsonRpcError(ErrorCode.MethodNotFound, s"this server does not serve $capability".assumeNonEmpty))
        )

  private def withParams[P: Decoder](params: Option[Json])(
      run: P => IO[Either[JsonRpcError, Json]]
  ): IO[Either[JsonRpcError, Json]] =
    params.getOrElse(Json.obj()).as[P] match
      case Right(decoded) => run(decoded)
      case Left(failure) =>
        IO.pure(
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

  private def guarded(meta: RequestMeta)(run: => IO[Json]): IO[Either[JsonRpcError, Json]] =
    checkVersion(meta) match
      case Left(error) => IO.pure(Left(error))
      case Right(_)    => run.map(Right(_))

extension (self: String)
  private[server] def assumeNonEmpty: NonEmptyString =
    if self.isEmpty then "unspecified error" else self.asInstanceOf[NonEmptyString]

extension [A](self: Option[A])
  private[server] def as[B](value: => B): Option[B] = self.map(_ => value)
