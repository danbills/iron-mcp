package ironmcp
package auth

import cats.Functor
import cats.syntax.all.*
import ironmcp.protocol.*
import ironmcp.server.ToolProvider

/** A [[ToolProvider]] that lists only the tools the caller's token may call,
  * as the C# SDK's `[Authorize]` filtering does (Handbook §9.2.7).
  *
  * Hiding a tool keeps it out of the model's choices; it is not what protects
  * it. A client can still call a tool it was not shown, and
  * [[McpAuthServer]] answers that call with `403 insufficient_scope`, so
  * `call` passes straight through.
  *
  * Build it per request, inside `McpAuthServer.routesFor`, from the validated
  * claims:
  * {{{
  * McpAuthServer.routesFor(validator, config) { (_, claims) =>
  *   IO.pure(Right(McpServer[IO](info, tools = Some(ScopedTools(tools, config.scopePolicy, claims)))))
  * }
  * }}}
  */
final class ScopedTools[F[_]: Functor](underlying: ToolProvider[F], policy: ToolScopePolicy, granted: Set[String])
    extends ToolProvider[F]:

  /** The list depends on the token, so no shared cache may keep it. */
  def list(params: ListToolsParams): F[ListToolsResult] =
    underlying.list(params).map { result =>
      result.copy(tools = result.tools.filter(tool => policy.visible(tool.name, granted)), cacheScope = CacheScope.`private`)
    }

  def call(params: CallToolParams): F[CallToolResult | InputRequiredResult] = underlying.call(params)

object ScopedTools:
  def apply[F[_]: Functor](underlying: ToolProvider[F], policy: ToolScopePolicy, claims: TokenClaims): ScopedTools[F] =
    new ScopedTools(underlying, policy, claims.scopes)
