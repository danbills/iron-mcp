package ironmcp
package client

import cats.MonadThrow
import cats.effect.{Ref, Temporal}
import cats.syntax.all.*
import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*

import scala.concurrent.duration.*

/** What a client can do for the server when asked mid-call: one handler per
  * kind of input request. Supplying a handler is what declares the matching
  * client capability, so the two cannot disagree (as on the server side, where
  * providers declare capabilities).
  */
final case class InputHandlers[F[_]](
    elicitation: Option[ElicitFormParams => F[ElicitResult]] = None,
    sampling: Option[CreateMessageParams => F[CreateMessageResult]] = None,
    roots: Option[F[List[Root]]] = None
):
  def capabilities: ClientCapabilities =
    ClientCapabilities(
      elicitation = elicitation.map(_ => ElicitationCapability(form = Some(JsonObject.empty))),
      sampling = sampling.map(_ => SamplingCapability()),
      roots = roots.map(_ => JsonObject.empty)
    )

  /** Both sets of handlers; where both have one, `other`'s wins. */
  def ++(other: InputHandlers[F]): InputHandlers[F] =
    InputHandlers(other.elicitation.orElse(elicitation), other.sampling.orElse(sampling), other.roots.orElse(roots))

object InputHandlers:
  def none[F[_]]: InputHandlers[F] = InputHandlers[F]()
  def elicitation[F[_]](handler: ElicitFormParams => F[ElicitResult]): InputHandlers[F] = InputHandlers(elicitation = Some(handler))

  /** Runs the server's `sampling/createMessage` on the client's model. */
  def sampling[F[_]](handler: CreateMessageParams => F[CreateMessageResult]): InputHandlers[F] = InputHandlers(sampling = Some(handler))

  /** Answers `roots/list`; `roots` is evaluated for each request, so it can change. */
  def roots[F[_]](roots: F[List[Root]]): InputHandlers[F] = InputHandlers(roots = Some(roots))

/** The answers to an `input_required` result, sent back on the retry. */
final case class Retry(requestState: Option[String], inputResponses: Map[String, Json])

object Retry:
  val none: Retry = Retry(None, Map.empty)

/** A client for revision 2026-07-28: every request carries its protocol
  * version, client info and capabilities in `_meta`, so there is no handshake
  * and nothing to keep between calls but the next request id.
  *
  * A JSON-RPC error is raised as [[McpClientError.Rpc]]. Calls that may answer
  * `input_required` return `A | InputRequiredResult`, for a caller that runs
  * its own retry loop; [[callToolGathering]] runs it with this client's
  * [[InputHandlers]].
  */
final class McpClient[F[_]] private (
    transport: ClientTransport[F],
    val info: Implementation,
    val handlers: InputHandlers[F],
    ids: Ref[F, Long]
)(using F: MonadThrow[F]):

  val capabilities: ClientCapabilities = handlers.capabilities

  private val baseMeta: RequestMeta = RequestMeta(LatestProtocolVersion, capabilities, Some(info))
  private val meta: Json            = baseMeta.asJson

  /** Sends one request; `params` gets the `_meta` envelope added, with
    * `progressToken` when the caller wants progress for it.
    */
  def request[R: Decoder](method: String, params: JsonObject = JsonObject.empty, progressToken: Option[ProgressToken] = None): F[R] =
    val envelope = progressToken.fold(meta)(token => baseMeta.copy(progressToken = Some(token)).asJson)
    for
      id      <- ids.updateAndGet(_ + 1)
      name    <- F.fromEither(method.refineEither[Not[Empty]].leftMap(_ => McpClientError.Malformed("empty method name")))
      message  = new JsonRpcMessage.Request(RequestId.Num(id), name, Some(Json.fromJsonObject(params.add("_meta", envelope))))
      reply   <- transport.request(message)
      result <- reply match
                  case JsonRpcMessage.Success(_, json) =>
                    F.fromEither(McpClient.completeByDefault(json).as[R].leftMap(failure => McpClientError.Malformed(s"$method: ${failure.getMessage}")))
                  case JsonRpcMessage.Failure(_, error) => F.raiseError(McpClientError.Rpc(error))
                  case other                            => F.raiseError(McpClientError.Malformed(s"$method: unexpected $other"))
    yield result

  def discover: F[DiscoverResult] = request[DiscoverResult](Method.Discover.wire)

  def listTools(cursor: Option[String] = None): F[ListToolsResult] =
    request[ListToolsResult](Method.ToolsList.wire, page(cursor))

  /** Every tool, following `nextCursor` to the end. */
  def allTools: F[List[Tool]] = all(listTools(_).map(r => (r.tools, r.nextCursor)))

  /** `tools/call`. With a `progressToken`, the server may send
    * `notifications/progress` for it, which reach the transport's
    * `onNotification`.
    */
  def callTool(
      name: String,
      arguments: JsonObject = JsonObject.empty,
      retry: Retry = Retry.none,
      progressToken: Option[ProgressToken] = None
  ): F[CallToolResult | InputRequiredResult] =
    request[CallToolResult | InputRequiredResult](
      Method.ToolsCall.wire,
      retrying(JsonObject("name" -> name.asJson, "arguments" -> Json.fromJsonObject(arguments)), retry),
      progressToken
    )(using McpClient.orInputRequired[CallToolResult])

  def listResources(cursor: Option[String] = None): F[ListResourcesResult] =
    request[ListResourcesResult](Method.ResourcesList.wire, page(cursor))

  def allResources: F[List[Resource]] = all(listResources(_).map(r => (r.resources, r.nextCursor)))

  def listResourceTemplates(cursor: Option[String] = None): F[ListResourceTemplatesResult] =
    request[ListResourceTemplatesResult](Method.ResourceTemplatesList.wire, page(cursor))

  def allResourceTemplates: F[List[ResourceTemplate]] =
    all(listResourceTemplates(_).map(r => (r.resourceTemplates, r.nextCursor)))

  def readResource(uri: String, retry: Retry = Retry.none): F[ReadResourceResult | InputRequiredResult] =
    request[ReadResourceResult | InputRequiredResult](Method.ResourcesRead.wire, retrying(JsonObject("uri" -> uri.asJson), retry))(
      using McpClient.orInputRequired[ReadResourceResult]
    )

  def listPrompts(cursor: Option[String] = None): F[ListPromptsResult] =
    request[ListPromptsResult](Method.PromptsList.wire, page(cursor))

  def getPrompt(name: String, arguments: Map[String, String] = Map.empty, retry: Retry = Retry.none): F[GetPromptResult | InputRequiredResult] =
    request[GetPromptResult | InputRequiredResult](
      Method.PromptsGet.wire,
      retrying(JsonObject("name" -> name.asJson, "arguments" -> arguments.asJson), retry)
    )(using McpClient.orInputRequired[GetPromptResult])

  def complete(ref: CompletionReference, argument: String, value: String, context: Map[String, String] = Map.empty): F[CompleteResult] =
    request[CompleteResult](
      Method.CompletionComplete.wire,
      JsonObject(
        "ref"      -> ref.asJson,
        "argument" -> Json.obj("name" -> argument.asJson, "value" -> value.asJson)
      ).addAll(Option.when(context.nonEmpty)("context" -> Json.obj("arguments" -> context.asJson)))
    )

  /** `tools/call`, answering every `input_required` round with this client's
    * [[InputHandlers]] and re-sending with the answers and the server's
    * `requestState` (echoed verbatim, never read).
    *
    * `deadline` covers the whole exchange, the time a user spends answering a
    * form included: it is one timeout around the loop, so it cancels a handler
    * still waiting on the user.
    */
  def callToolGathering(
      name: String,
      arguments: JsonObject = JsonObject.empty,
      maxRounds: Int = 10,
      deadline: FiniteDuration = 2.minutes
  )(using T: Temporal[F]): F[CallToolResult] =
    def round(n: Int, retry: Retry): F[CallToolResult] =
      if n > maxRounds then F.raiseError(McpClientError.RetryBudgetExhausted(maxRounds))
      else
        callTool(name, arguments, retry).flatMap {
          case result: CallToolResult => F.pure(result)
          case input: InputRequiredResult =>
            input.inputRequests.getOrElse(Map.empty).toList
              .traverse((key, request) => answer(key, request).map(key -> _))
              .flatMap(answers => round(n + 1, Retry(input.requestState, answers.toMap)))
        }
    T.timeoutTo(round(1, Retry.none), deadline, F.raiseError(McpClientError.GatherTimedOut(deadline)))

  /** Answers one embedded request with the matching handler. */
  def answer(key: String, request: Json): F[Json] =
    request.as[InputRequest] match
      case Left(failure) => F.raiseError(McpClientError.Malformed(s"input request '$key': ${failure.getMessage}"))
      case Right(InputRequest(method, params)) =>
        def decoded[P: Decoder]: F[P] =
          F.fromEither(params.as[P].leftMap(f => McpClientError.Malformed(s"input request '$key': ${f.getMessage}")))
        val answered: Option[F[Json]] = (method: String) match
          case "elicitation/create"     => handlers.elicitation.map(h => decoded[ElicitFormParams].flatMap(h).map(_.asJson))
          case "sampling/createMessage" => handlers.sampling.map(h => decoded[CreateMessageParams].flatMap(h).map(_.asJson))
          case "roots/list"             => handlers.roots.map(_.map(roots => ListRootsResult(roots).asJson))
          case _                        => None
        answered.fold(F.raiseError(McpClientError.UnsupportedInputRequest(key, method)))(_.map(_.deepDropNullValues))

  private def page(cursor: Option[String]): JsonObject = JsonObject.fromIterable(cursor.map("cursor" -> _.asJson))

  private def retrying(params: JsonObject, retry: Retry): JsonObject =
    params
      .addAll(retry.requestState.map("requestState" -> _.asJson))
      .addAll(Option.when(retry.inputResponses.nonEmpty)("inputResponses" -> retry.inputResponses.asJson))

  private def all[A](fetch: Option[String] => F[(List[A], Option[Cursor])]): F[List[A]] =
    F.tailRecM((Option.empty[String], List.empty[A])) { (cursor, acc) =>
      fetch(cursor).map { (items, next) =>
        next.map(c => (Some(c: String), acc ++ items)).toLeft(acc ++ items)
      }
    }

object McpClient:

  def apply[F[_]](
      transport: ClientTransport[F],
      info: Implementation = Implementation(name = "iron-mcp-client", version = "0.5.0"),
      handlers: InputHandlers[F] = InputHandlers.none[F]
  )(using F: MonadThrow[F], R: Ref.Make[F]): F[McpClient[F]] =
    Ref.of[F, Long](0L).map(ids => new McpClient(transport, info, handlers, ids))

  /** "When a client receives a result from a server implementing an earlier
    * protocol version (which does not include `resultType`), the client MUST
    * treat the absent field as `complete`."
    */
  private[client] def completeByDefault(result: Json): Json =
    result.mapObject(obj => if obj.contains("resultType") then obj else obj.add("resultType", Json.fromString("complete")))

  /** `A | InputRequiredResult`, told apart by `resultType` as the server's dispatcher writes it. */
  def orInputRequired[A: Decoder]: Decoder[A | InputRequiredResult] = Decoder.instance { cursor =>
    cursor.get[Option[String]]("resultType").flatMap {
      case Some("input_required") => cursor.as[InputRequiredResult]
      case _                      => cursor.as[A]
    }
  }

extension (self: JsonObject)
  private def addAll(entries: IterableOnce[(String, Json)]): JsonObject =
    entries.iterator.foldLeft(self)((obj, entry) => obj.add(entry._1, entry._2))
