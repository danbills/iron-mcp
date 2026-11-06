package ironmcp
package auth

import cats.effect.Temporal
import cats.syntax.all.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import ironmcp.http.{McpHeaders, McpHttp}
import ironmcp.server.ServerHandler
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** An MCP endpoint as an OAuth 2.1 resource server.
  *
  * Every request goes through the same ladder, and the first failed rung
  * answers:
  *
  * | Rung                                    | Answer                                   |
  * |-----------------------------------------|------------------------------------------|
  * | `Authorization` malformed or repeated   | `400 invalid_request`                    |
  * | no token, target not public             | `401` with `resource_metadata`, `scope`  |
  * | token invalid (signature, `exp`, `aud`, `iss`, `typ`, revoked, ...) | `401 invalid_token`  |
  * | signing keys unreachable                | `503`                                    |
  * | target not in the policy                | `403`                                    |
  * | token lacks the target's scopes         | `403 insufficient_scope` with the full set |
  * | otherwise                               | the server built for these claims        |
  *
  * The target (tool, resource, prompt) is read from the `Mcp-Method` and
  * `Mcp-Name` headers, which [[McpHttp]] has already checked against the body
  * before this runs, so a client cannot name one thing in the header and call
  * another.
  */
object McpAuthServer:

  /** @param resource             this endpoint's public URL; the routes are
    *                             mounted on its path
    * @param authorizationServers the issuers clients may get tokens from
    * @param policy               who may use what ([[AuthPolicy]], denying by default)
    * @param http                 the rest of the transport settings (origins,
    *                             body size, CORS); its `path` is replaced by
    *                             the resource's
    * @param scopesSupported      advertised scopes; by default every scope the
    *                             policy mentions
    */
  final case class Config(
      resource: ResourceIdentity,
      authorizationServers: List[String],
      policy: AuthPolicy,
      http: McpHttp.Config = McpHttp.Config(),
      resourceName: Option[String] = None,
      documentation: Option[String] = None,
      scopesSupported: Option[Set[String]] = None
  ):
    def mcpConfig: McpHttp.Config =
      http.copy(path = resource.path.refineOption[StartWith["/"] & Not[Empty]].getOrElse("/mcp"))

    def resourceMetadataUrl: String = resource.metadataUrl

    def metadata: ProtectedResourceMetadata =
      ProtectedResourceMetadata(
        resource = resource.resource,
        authorization_servers = authorizationServers,
        scopes_supported = Some(scopesSupported.getOrElse(policy.scopes).toList.sorted),
        resource_name = resourceName,
        resource_documentation = documentation
      )

  /** One server for every caller. */
  def routes[F[_]: Temporal](server: ServerHandler[F], validator: TokenValidator[F], config: Config): HttpRoutes[F] =
    routes(server, validator, config, AuthAudit.noop[F])

  def routes[F[_]: Temporal](server: ServerHandler[F], validator: TokenValidator[F], config: Config, audit: AuthAudit[F]): HttpRoutes[F] =
    routesFor(validator, config, audit)((_, _) => Temporal[F].pure(Right(server)))

  /** Every request needs a valid token; `serverFor` builds the server for its
    * claims (filter tools with [[ScopedTools]], close tools over the caller).
    */
  def routesFor[F[_]: Temporal](validator: TokenValidator[F], config: Config)(
      serverFor: (Request[F], TokenClaims) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    routesFor(validator, config, AuthAudit.noop[F])(serverFor)

  def routesFor[F[_]: Temporal](validator: TokenValidator[F], config: Config, audit: AuthAudit[F])(
      serverFor: (Request[F], TokenClaims) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    build(validator, config, audit, optional = false) { (req, caller) =>
      caller match
        case Some(claims) => serverFor(req, claims)
        case None         => Temporal[F].pure(Left(AuthChallenge.unauthorized[F](AuthError.MissingToken, config.resourceMetadataUrl)))
    }

  /** Sign-in is needed only for what the policy does not mark
    * [[Access.Public]]: anonymous callers reach public tools (and, with
    * [[AuthPolicy.publicListing]], the listings), and the first protected call
    * gets the `401` that starts the client's sign-in.
    *
    * A token that is present but invalid is always refused, never treated as
    * anonymous: a client must learn its token is bad.
    */
  def routesForOptional[F[_]: Temporal](validator: TokenValidator[F], config: Config)(
      serverFor: (Request[F], Option[TokenClaims]) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    routesForOptional(validator, config, AuthAudit.noop[F])(serverFor)

  def routesForOptional[F[_]: Temporal](validator: TokenValidator[F], config: Config, audit: AuthAudit[F])(
      serverFor: (Request[F], Option[TokenClaims]) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    build(validator, config, audit, optional = true)(serverFor)

  /** The RFC 9728 document, at the resource's metadata path and at the root
    * well-known path (clients fall back to it).
    */
  def metadataRoutes[F[_]: Temporal](config: Config): HttpRoutes[F] =
    val paths = Set(config.resource.metadataPath, ResourceIdentity.WellKnown)
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && paths.contains(req.uri.path.renderString) =>
        Temporal[F].pure(
          Response[F](Status.Ok)
            .withEntity(config.metadata.asJson)
            .withContentType(`Content-Type`(MediaType.application.json))
            .putHeaders(Header.Raw(CIString("Access-Control-Allow-Origin"), "*"))
        )
    }

  private val Authorization = CIString("Authorization")
  private val token68 = "^[A-Za-z0-9\\-._~+/]+=*$".r

  /** The token in `Authorization: Bearer <token>`, `None` if there is no
    * header; refused if the header is repeated, malformed, or the request
    * also carries `access_token` in its query (which MCP forbids).
    */
  private[auth] def bearer[F[_]](req: Request[F]): Either[AuthError, Option[String]] =
    if req.uri.query.params.contains("access_token") then Left(AuthError.InvalidRequest("access_token in the query"))
    else
      req.headers.get(Authorization).map(_.toList) match
        case None => Right(None)
        case Some(_ :: _ :: _) => Left(AuthError.InvalidRequest("more than one Authorization header"))
        case Some(h :: Nil) =>
          h.value.trim.split(" +", 2) match
            case Array(scheme, value) if scheme.equalsIgnoreCase("Bearer") =>
              if token68.matches(value) then Right(Some(value)) else Left(AuthError.InvalidRequest("the token has characters a bearer token cannot"))
            case _ => Right(None) // another scheme (Basic, ...) is no bearer token
        case Some(Nil) => Right(None)

  private def build[F[_]](validator: TokenValidator[F], config: Config, audit: AuthAudit[F], optional: Boolean)(
      serverFor: (Request[F], Option[TokenClaims]) => F[Either[Response[F], ServerHandler[F]]]
  )(using F: Temporal[F]): HttpRoutes[F] =
    val metadataUrl = config.resourceMetadataUrl
    val mcp = McpHttp.routesFor[F](config.mcpConfig) { req =>
      val method = req.headers.get(McpHeaders.Method).map(_.head.value).getOrElse("")
      val name   = req.headers.get(McpHeaders.Name).map(_.head.value).flatMap(McpHeaders.decode)
      val target = Target.of(method, name)
      val access = config.policy.access(target)

      def record(allowed: Boolean, reason: String, detail: Option[String], caller: Option[TokenClaims]): F[Unit] =
        F.realTime.flatMap { now =>
          audit.record(
            AuthEvent(
              at = now.toMillis,
              allowed = allowed,
              reason = reason,
              detail = detail,
              method = method,
              target = target.label,
              subject = caller.flatMap(_.sub),
              clientId = caller.flatMap(_.clientId),
              issuer = caller.map(_.iss),
              jti = caller.flatMap(_.jti),
              jkt = caller.flatMap(_.boundKeyThumbprint),
              required = access.fold(Set.empty[String])(_.required),
              granted = caller.fold(Set.empty[String])(_.scopes)
            )
          )
        }

      def refuse(error: AuthError): F[Either[Response[F], ServerHandler[F]]] =
        record(false, error.kind, Some(error.description), None)
          .as(Left(AuthChallenge.unauthorized[F](error, metadataUrl, scope = access.fold(Set.empty[String])(_.required))))

      val authenticated: F[Either[AuthError, Option[TokenClaims]]] =
        bearer(req) match
          case Left(error)        => F.pure(Left(error))
          case Right(None)        => F.pure(Right(None))
          case Right(Some(token)) =>
            validator.validate(token).map(_.map(Some(_))).handleError(e => Left(AuthError.KeysUnavailable(Option(e.getMessage).getOrElse(e.toString))))

      authenticated.flatMap {
        case Left(error) => refuse(error)
        case Right(None) =>
          access match
            case Some(Access.Public) if optional => record(true, "public", None, None) *> serverFor(req, None)
            case _                               => refuse(AuthError.MissingToken)
        case Right(caller @ Some(claims)) =>
          access match
            case None =>
              record(false, "unlisted", Some(s"${target.label} is not in the policy"), caller)
                .as(Left(AuthChallenge.forbidden[F](Set.empty, metadataUrl)))
            case Some(a) if a.allows(caller) =>
              record(true, "ok", None, caller) *> serverFor(req, caller)
            case Some(a) =>
              val error = AuthError.InsufficientScope(a.required, claims.scopes)
              record(false, error.kind, Some(error.description), caller)
                .as(Left(AuthChallenge.forbidden[F](a.required, metadataUrl)))
      }
    }
    metadataRoutes(config) <+> mcp
