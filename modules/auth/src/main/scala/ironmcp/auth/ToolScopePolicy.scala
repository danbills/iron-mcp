package ironmcp
package auth

/** Per-tool OAuth scope policy for MCP servers (Handbook §9.2.7 and §9.7.4).
  *
  * When a caller invokes a tool without holding all required scopes, the server
  * returns HTTP 403 Forbidden with a `WWW-Authenticate` challenge containing
  * `scope="<all-required-scopes>"`.
  */
trait ToolScopePolicy:
  /** Returns the full set of scopes required by the given tool.
    * Returns an empty set if the tool requires no specific scopes.
    */
  def requiredScopes(toolName: String): Set[String]

  /** Checks whether the granted scopes satisfy the tool's requirements.
    *
    * @return `Right(())` if authorized;
    *         `Left(requiredScopes)` naming the FULL required set if insufficient.
    */
  def check(toolName: String, granted: Set[String]): Either[Set[String], Unit] =
    val required = requiredScopes(toolName)
    if required.subsetOf(granted) then Right(())
    else Left(required)

object ToolScopePolicy:

  /** Empty policy where all tools are accessible to any authenticated caller. */
  val open: ToolScopePolicy = new ToolScopePolicy:
    def requiredScopes(toolName: String): Set[String] = Set.empty

  /** Creates a policy from a Map of tool name to required scopes. */
  def exact(map: Map[String, Set[String]]): ToolScopePolicy = new ToolScopePolicy:
    def requiredScopes(toolName: String): Set[String] = map.getOrElse(toolName, Set.empty)

  /** Fluent builder for configuring per-tool scope requirements. */
  final class Builder private (private val rules: Map[String, Set[String]]):
    def require(toolName: String, scopes: String*): Builder =
      new Builder(rules.updated(toolName, scopes.toSet))

    def require(toolName: String, scopes: Set[String]): Builder =
      new Builder(rules.updated(toolName, scopes))

    def build: ToolScopePolicy = exact(rules)

  object Builder:
    def empty: Builder = new Builder(Map.empty)
