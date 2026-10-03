package ironmcp
package server

import cats.Applicative
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

trait ResourceProvider[F[_]](using F: Applicative[F]):
  def list(params: ListResourcesParams): F[ListResourcesResult]
  def read(params: ReadResourceParams): F[ReadResourceResult | InputRequiredResult]
  def templates(params: ListResourcesParams): F[ListResourceTemplatesResult] =
    F.pure(ListResourceTemplatesResult(Nil, 0L, CacheScope.`private`))

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
