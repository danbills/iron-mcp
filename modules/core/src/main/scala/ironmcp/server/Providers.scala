package ironmcp
package server

import cats.effect.IO
import ironmcp.protocol.*

/** What a server can offer. Each provider maps one-to-one onto a capability:
  * declaring the provider is what turns the capability on, so the two cannot
  * disagree.
  *
  * The union returns mirror the spec exactly — `tools/call`, `prompts/get` and
  * `resources/read` may answer `input_required`; nothing else may, and nothing
  * else can, because no other signature admits it.
  */
trait ToolProvider:
  def list(params: ListToolsParams): IO[ListToolsResult]
  def call(params: CallToolParams): IO[CallToolResult | InputRequiredResult]

trait ResourceProvider:
  def list(params: ListResourcesParams): IO[ListResourcesResult]
  def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult]
  def templates(params: ListResourcesParams): IO[ListResourceTemplatesResult] =
    IO.pure(ListResourceTemplatesResult(Nil, 0L, CacheScope.`private`))

trait PromptProvider:
  def list(params: ListPromptsParams): IO[ListPromptsResult]
  def get(params: GetPromptParams): IO[GetPromptResult | InputRequiredResult]

trait CompletionProvider:
  def complete(params: CompleteParams): IO[CompleteResult]

/** Subscriptions are a streaming concern. A stateless server accepts the
  * request and hands back an id; whether anything is ever delivered on it is
  * the transport's business, not the protocol's.
  */
trait SubscriptionProvider:
  def listen(params: SubscriptionsListenParams): IO[SubscriptionsListenResult]
