package ironmcp
package auth

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.McpTool

/** Who may use one tool, resource or prompt. */
enum Access:
  /** Anyone, signed in or not (with [[McpAuthServer.routesForOptional]]). */
  case Public

  /** A signed-in caller whose token grants every one of `scopes` (none: any
    * valid token).
    */
  case Scopes(scopes: Set[String])

  def required: Set[String] = this match
    case Public          => Set.empty
    case Scopes(scopes)  => scopes

  def allows(caller: Option[TokenClaims]): Boolean = this match
    case Public         => true
    case Scopes(scopes) => caller.exists(_.hasScopes(scopes))

object Access:
  /** Any valid token. */
  val SignedIn: Access = Scopes(Set.empty)

  /** The `_meta` key a tool's access is declared under. Hosts and Views may
    * read it to show what is locked; it is advice for display, and what
    * protects the tool is the [[AuthPolicy]] built from it.
    */
  val Key: MetaKey = "io.github.danbills/auth"

  given Encoder[Access] = Encoder.instance {
    case Public         => Json.obj("public" -> true.asJson)
    case Scopes(scopes) => Json.obj("scopes" -> scopes.toList.sorted.asJson)
  }

  given Decoder[Access] = Decoder.instance { c =>
    c.getOrElse[Boolean]("public")(false).flatMap {
      case true  => Right(Public)
      case false => c.getOrElse[List[String]]("scopes")(Nil).map(s => Scopes(s.toSet))
    }
  }

  /** The access a tool definition declares, if any. */
  def of(tool: Tool): Option[Access] =
    tool._meta.flatMap(_.get(Key)).flatMap(_.as[Access].toOption)

extension [F[_]](tool: McpTool[F])
  /** Callable only with a token granting every one of `scopes`. */
  def requires(scope: String, more: String*): McpTool[F] = tool.withAccess(Access.Scopes((scope +: more).toSet))

  /** Callable with any valid token. */
  def signedIn: McpTool[F] = tool.withAccess(Access.SignedIn)

  /** Callable by anyone, signed in or not. */
  def public: McpTool[F] = tool.withAccess(Access.Public)

  def withAccess(access: Access): McpTool[F] =
    val meta = tool.definition._meta.getOrElse(MetaObject.empty) ++ Map(Access.Key -> access.asJson)
    tool.copy(definition = tool.definition.copy(_meta = Some(meta)))

/** What a request is about, as far as authorization goes. */
enum Target:
  case Tool(name: String)
  case Resource(uri: String)
  case Prompt(name: String)

  /** Any other method: listing, discovery, tasks, ping. */
  case Method(name: String)

  def label: String = this match
    case Tool(n)     => s"tool:$n"
    case Resource(u) => s"resource:$u"
    case Prompt(n)   => s"prompt:$n"
    case Method(m)   => m

object Target:
  /** The target of a request with method `method` and mirrored `Mcp-Name`
    * value `name`. McpHttp has already checked both against the body.
    */
  def of(method: String, name: Option[String]): Target = (method, name) match
    case ("tools/call", Some(n))     => Tool(n)
    case ("resources/read", Some(u)) => Resource(u)
    case ("prompts/get", Some(n))    => Prompt(n)
    case (m, _)                      => Method(m)

/** Who may do what. It **denies by default**: a tool, resource or prompt the
  * policy does not name is refused for everyone, so forgetting a rule closes
  * a door rather than opening one.
  *
  * Build it from the tools themselves ([[AuthPolicy.fromTools]]), where each
  * tool says what it needs with `.requires(...)`, `.signedIn` or `.public`;
  * then add resources by URI prefix and prompts by name.
  *
  * @param tools     access per tool name
  * @param resources access per URI prefix; the longest matching prefix wins
  * @param prompts   access per prompt name
  * @param methods   access for everything else (`tools/list`,
  *                  `server/discover`, `tasks/get`, ...); `SignedIn` by
  *                  default, `Public` lets anonymous callers see what exists
  */
final case class AuthPolicy(
    tools: Map[String, Access] = Map.empty,
    resources: List[(String, Access)] = Nil,
    prompts: Map[String, Access] = Map.empty,
    methods: Access = Access.SignedIn
):

  /** The access `target` needs, or `None` if the policy does not name it (and
    * so nobody may use it).
    */
  def access(target: Target): Option[Access] = target match
    case Target.Tool(name)     => tools.get(name)
    case Target.Resource(uri)  => resources.filter((prefix, _) => uri.startsWith(prefix)).maxByOption(_._1.length).map(_._2)
    case Target.Prompt(name)   => prompts.get(name)
    case Target.Method(_)      => Some(methods)

  /** Whether `caller` may use `target`. */
  def allows(target: Target, caller: Option[TokenClaims]): Boolean = access(target).exists(_.allows(caller))

  def tool(name: String, access: Access): AuthPolicy = copy(tools = tools.updated(name, access))

  def tool(name: String, scope: String, more: String*): AuthPolicy = tool(name, Access.Scopes((scope +: more).toSet))

  def resource(uriPrefix: String, access: Access): AuthPolicy = copy(resources = resources :+ (uriPrefix -> access))

  def prompt(name: String, access: Access): AuthPolicy = copy(prompts = prompts.updated(name, access))

  /** MCP Apps Views (`ui://` resources), by default readable by anyone: a
    * host fetches a View's HTML before any sign-in, and the page holds no
    * user data (that comes from tool results, which the tools' own rules
    * protect).
    */
  def views(access: Access = Access.Public): AuthPolicy = resource("ui://", access)

  /** Lets anonymous callers list and discover (for optional sign-in). */
  def publicListing: AuthPolicy = copy(methods = Access.Public)

  /** Adds the tools' own declarations. */
  def withTools[F[_]](defined: McpTool[F]*): AuthPolicy =
    copy(tools = tools ++ defined.flatMap(t => Access.of(t.definition).map((t.definition.name: String) -> _)))

  /** Every scope the policy mentions: the metadata's `scopes_supported`. */
  def scopes: Set[String] =
    (tools.values ++ resources.map(_._2) ++ prompts.values ++ List(methods)).flatMap(_.required).toSet

  /** The tools among `defined` this policy says nothing about. Nobody can call
    * them; call this at startup and fail if it is not empty.
    */
  def undeclared[F[_]](defined: McpTool[F]*): List[String] =
    defined.map(_.definition.name: String).filterNot(tools.contains).toList

object AuthPolicy:
  /** The policy declared on `tools` with `.requires`, `.signedIn` and `.public`. */
  def fromTools[F[_]](tools: McpTool[F]*): AuthPolicy = AuthPolicy().withTools(tools*)
