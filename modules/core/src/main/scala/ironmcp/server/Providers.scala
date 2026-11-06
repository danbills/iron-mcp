package ironmcp
package server

import cats.{Applicative, MonadThrow}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*

/** What a server can offer. Each provider maps one-to-one onto a capability:
  * declaring the provider is what turns the capability on, so the two cannot
  * disagree.
  *
  * Providers are polymorphic in the effect `F`: the server only ever lifts and
  * maps their results, so nothing here forces `IO`.
  *
  * The union returns mirror the spec exactly — `tools/call`, `prompts/get` and
  * `resources/read` may answer `input_required`; nothing else may, and nothing
  * else can, because no other signature admits it.
  */
trait ToolProvider[F[_]]:
  def list(params: ListToolsParams): F[ListToolsResult]
  def call(params: CallToolParams): F[CallToolResult | InputRequiredResult]

  /** [[call]], for a tool that reports progress while it runs. */
  def callEmitting(params: CallToolParams, @annotation.unused emit: Emit[F]): F[CallToolResult | InputRequiredResult] =
    call(params)

trait ResourceProvider[F[_]](using F: Applicative[F]):
  def list(params: ListResourcesParams): F[ListResourcesResult]
  def read(params: ReadResourceParams): F[ReadResourceResult | InputRequiredResult]
  def templates(@annotation.unused params: ListResourcesParams): F[ListResourceTemplatesResult] =
    F.pure(ListResourceTemplatesResult(Nil, 0L, CacheScope.`private`))

object ResourceProvider:

  /** Several providers as one: their resources and templates listed together,
    * and a read tried on each in turn until one knows the URI (any answer but
    * `-32002`). A result is cached as briefly, and as privately, as the
    * strictest of its parts. Lists are not paginated across providers.
    */
  def all[F[_]](first: ResourceProvider[F], rest: ResourceProvider[F]*)(using F: MonadThrow[F]): ResourceProvider[F] =
    val providers = first :: rest.toList
    new ResourceProvider[F]:
      def list(params: ListResourcesParams): F[ListResourcesResult] =
        providers.traverse(_.list(params)).map(results =>
          ListResourcesResult(results.flatMap(_.resources), shortest(results.map(_.ttlMs)), strictest(results.map(_.cacheScope)))
        )

      override def templates(params: ListResourcesParams): F[ListResourceTemplatesResult] =
        providers.traverse(_.templates(params)).map(results =>
          ListResourceTemplatesResult(
            results.flatMap(_.resourceTemplates),
            shortest(results.map(_.ttlMs)),
            strictest(results.map(_.cacheScope))
          )
        )

      def read(params: ReadResourceParams): F[ReadResourceResult | InputRequiredResult] =
        providers.tail.foldLeft(providers.head.read(params)) { (attempt, next) =>
          attempt.recoverWith { case McpException(error) if error.code == ErrorCode.ResourceNotFound => next.read(params) }
        }

  private def shortest(ttls: List[CacheTtlMs]): CacheTtlMs = ttls.minBy(t => t: Long)

  private def strictest(scopes: List[CacheScope]): CacheScope =
    if scopes.contains(CacheScope.`private`) then CacheScope.`private` else CacheScope.`public`

trait PromptProvider[F[_]]:
  def list(params: ListPromptsParams): F[ListPromptsResult]
  def get(params: GetPromptParams): F[GetPromptResult | InputRequiredResult]

trait CompletionProvider[F[_]]:
  def complete(params: CompleteParams): F[CompleteResult]

/** Subscriptions are a streaming concern. A stateless server accepts the
  * request and hands back an id; whether anything is ever delivered on it is
  * the transport's business, not the protocol's.
  */
trait SubscriptionProvider[F[_]]:
  def listen(params: SubscriptionsListenParams): F[SubscriptionsListenResult]
