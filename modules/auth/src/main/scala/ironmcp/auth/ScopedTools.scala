package ironmcp
package auth

import cats.{Applicative, MonadThrow}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.*

/** A [[ToolProvider]] that shows a caller only the tools it may call, and
  * refuses the others itself as well.
  *
  * [[McpAuthServer]] refuses a call the policy does not allow before it gets
  * here; this is the second check, so a server reached some other way (a test
  * harness, a future transport) is still not open. Hiding a tool keeps it out
  * of the model's choices; refusing it is what protects it.
  *
  * Build it per request, from the claims `routesFor` hands you:
  * {{{
  * McpAuthServer.routesFor(validator, config) { (_, claims) =>
  *   IO.pure(Right(McpServer[IO](info, tools = Some(ScopedTools(tools, config.policy, Some(claims))))))
  * }
  * }}}
  */
final class ScopedTools[F[_]](underlying: ToolProvider[F], policy: AuthPolicy, caller: Option[TokenClaims])(using F: Applicative[F])
    extends ToolProvider[F]:

  private def allowed(name: String) = policy.allows(Target.Tool(name), caller)

  /** The list depends on the token, so no shared cache may keep it. */
  def list(params: ListToolsParams): F[ListToolsResult] =
    underlying.list(params).map(r => r.copy(tools = r.tools.filter(t => allowed(t.name)), cacheScope = CacheScope.`private`))

  def call(params: CallToolParams): F[CallToolResult | InputRequiredResult] = callEmitting(params, Emit.none[F])

  override def callEmitting(params: CallToolParams, emit: Emit[F]): F[CallToolResult | InputRequiredResult] =
    if allowed(params.name) then underlying.callEmitting(params, emit)
    else F.pure(CallToolResult.failed(s"'${params.name}' is not permitted for this caller"))

object ScopedTools:
  def apply[F[_]: Applicative](underlying: ToolProvider[F], policy: AuthPolicy, caller: Option[TokenClaims]): ScopedTools[F] =
    new ScopedTools(underlying, policy, caller)

/** Resources the caller may read, by the policy's URI prefixes. A resource
  * it may not read is "not found", so its existence is not given away.
  */
final class ScopedResources[F[_]](underlying: ResourceProvider[F], policy: AuthPolicy, caller: Option[TokenClaims])(using F: MonadThrow[F])
    extends ResourceProvider[F]:

  private def allowed(uri: String) = policy.allows(Target.Resource(uri), caller)

  def list(params: ListResourcesParams): F[ListResourcesResult] =
    underlying.list(params).map(r => r.copy(resources = r.resources.filter(x => allowed(x.uri)), cacheScope = CacheScope.`private`))

  def read(params: ReadResourceParams): F[ReadResourceResult | InputRequiredResult] =
    if allowed(params.uri) then underlying.read(params) else F.raiseError(McpException.resourceNotFound(params.uri))

  override def templates(params: ListResourcesParams): F[ListResourceTemplatesResult] =
    underlying.templates(params).map(_.copy(cacheScope = CacheScope.`private`))

/** Prompts the caller may get. A prompt it may not get is unknown to it. */
final class ScopedPrompts[F[_]](underlying: PromptProvider[F], policy: AuthPolicy, caller: Option[TokenClaims])(using F: MonadThrow[F])
    extends PromptProvider[F]:

  private def allowed(name: String) = policy.allows(Target.Prompt(name), caller)

  def list(params: ListPromptsParams): F[ListPromptsResult] =
    underlying.list(params).map(r => r.copy(prompts = r.prompts.filter(p => allowed(p.name)), cacheScope = CacheScope.`private`))

  def get(params: GetPromptParams): F[GetPromptResult | InputRequiredResult] =
    if allowed(params.name) then underlying.get(params)
    else F.raiseError(McpException(JsonRpcError(ErrorCode.InvalidParams, "Unknown prompt")))
