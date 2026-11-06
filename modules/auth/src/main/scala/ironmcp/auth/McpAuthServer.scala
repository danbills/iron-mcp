package ironmcp
package auth

import cats.effect.Async
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
  * | DPoP-bound token without a valid proof, or as Bearer | `401 invalid_dpop_proof` / `invalid_token` |
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
      scopesSupported: Option[Set[String]] = None,
      dpop: DpopPolicy = DpopPolicy.Allowed,
      dpopSettings: Dpop.Settings = Dpop.Settings()
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
        resource_documentation = documentation,
        dpop_signing_alg_values_supported = Some(dpopSettings.algorithms.map(_.name).toList.sorted),
        dpop_bound_access_tokens_required = Option.when(dpop == DpopPolicy.Required)(true)
      )

    /** The schemes challenges offer. */
    private[auth] def schemes: List[String] = dpop match
      case DpopPolicy.Allowed  => List("Bearer", "DPoP")
      case DpopPolicy.Required => List("DPoP")

  /** What the routes share across requests: where decisions are recorded,
    * and which DPoP proofs have been used.
    */
  final case class Hooks[F[_]](audit: AuthAudit[F], replay: ReplayCache[F])

  object Hooks:
    def default[F[_]: Async]: Hooks[F] = Hooks(AuthAudit.noop[F], ReplayCache.unsafeInMemory[F]())
    def audited[F[_]: Async](audit: AuthAudit[F]): Hooks[F] = Hooks(audit, ReplayCache.unsafeInMemory[F]())

  /** One server for every caller. */
  def routes[F[_]: Async](server: ServerHandler[F], validator: TokenValidator[F], config: Config): HttpRoutes[F] =
    routes(server, validator, config, Hooks.default[F])

  def routes[F[_]: Async](server: ServerHandler[F], validator: TokenValidator[F], config: Config, hooks: Hooks[F]): HttpRoutes[F] =
    routesFor(validator, config, hooks)((_, _) => Async[F].pure(Right(server)))

  /** Every request needs a valid token; `serverFor` builds the server for its
    * claims (filter tools with [[ScopedTools]], close tools over the caller).
    */
  def routesFor[F[_]: Async](validator: TokenValidator[F], config: Config)(
      serverFor: (Request[F], TokenClaims) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    routesFor(validator, config, Hooks.default[F])(serverFor)

  def routesFor[F[_]: Async](validator: TokenValidator[F], config: Config, audit: AuthAudit[F])(
      serverFor: (Request[F], TokenClaims) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    routesFor(validator, config, Hooks.audited(audit))(serverFor)

  def routesFor[F[_]: Async](validator: TokenValidator[F], config: Config, hooks: Hooks[F])(
      serverFor: (Request[F], TokenClaims) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    build(validator, config, hooks, optional = false) { (req, caller) =>
      caller match
        case Some(claims) => serverFor(req, claims)
        case None         => Async[F].pure(Left(AuthChallenge.unauthorized[F](AuthError.MissingToken, config.resourceMetadataUrl)))
    }

  /** Sign-in is needed only for what the policy does not mark
    * [[Access.Public]]: anonymous callers reach public tools (and, with
    * [[AuthPolicy.publicListing]], the listings), and the first protected call
    * gets the `401` that starts the client's sign-in.
    *
    * A token that is present but invalid is always refused, never treated as
    * anonymous: a client must learn its token is bad.
    */
  def routesForOptional[F[_]: Async](validator: TokenValidator[F], config: Config)(
      serverFor: (Request[F], Option[TokenClaims]) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    routesForOptional(validator, config, Hooks.default[F])(serverFor)

  def routesForOptional[F[_]: Async](validator: TokenValidator[F], config: Config, audit: AuthAudit[F])(
      serverFor: (Request[F], Option[TokenClaims]) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    routesForOptional(validator, config, Hooks.audited(audit))(serverFor)

  def routesForOptional[F[_]: Async](validator: TokenValidator[F], config: Config, hooks: Hooks[F])(
      serverFor: (Request[F], Option[TokenClaims]) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    build(validator, config, hooks, optional = true)(serverFor)

  /** The RFC 9728 document, at the resource's metadata path and at the root
    * well-known path (clients fall back to it).
    */
  def metadataRoutes[F[_]: Async](config: Config): HttpRoutes[F] =
    val paths = Set(config.resource.metadataPath, ResourceIdentity.WellKnown)
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && paths.contains(req.uri.path.renderString) =>
        Async[F].pure(
          Response[F](Status.Ok)
            .withEntity(config.metadata.asJson)
            .withContentType(`Content-Type`(MediaType.application.json))
            .putHeaders(Header.Raw(CIString("Access-Control-Allow-Origin"), "*"))
        )
    }

  private val Authorization = CIString("Authorization")
  private val token68 = "^[A-Za-z0-9\\-._~+/]+=*$".r

  /** How a token arrived. */
  private[auth] enum Presented(val token: String):
    case Bearer(value: String) extends Presented(value)
    case Dpop(value: String, proof: String) extends Presented(value)

  /** The token in `Authorization: Bearer <token>` or `Authorization: DPoP
    * <token>` (with its one `DPoP` proof header), `None` if there is none.
    * Refused if the header is repeated or malformed, if there is not exactly
    * one proof for a DPoP token, or if the request also carries
    * `access_token` in its query (which MCP forbids).
    */
  private[auth] def presented[F[_]](req: Request[F]): Either[AuthError, Option[Presented]] =
    if req.uri.query.params.contains("access_token") then Left(AuthError.InvalidRequest("access_token in the query"))
    else
      req.headers.get(Authorization).map(_.toList) match
        case None => Right(None)
        case Some(_ :: _ :: _) => Left(AuthError.InvalidRequest("more than one Authorization header"))
        case Some(h :: Nil) =>
          h.value.trim.split(" +", 2) match
            case Array(scheme, value) if scheme.equalsIgnoreCase("Bearer") || scheme.equalsIgnoreCase("DPoP") =>
              if !token68.matches(value) then Left(AuthError.InvalidRequest("the token has characters a token cannot"))
              else if scheme.equalsIgnoreCase("Bearer") then Right(Some(Presented.Bearer(value)))
              else
                req.headers.get(Dpop.HeaderName).map(_.toList) match
                  case Some(p :: Nil) => Right(Some(Presented.Dpop(value, p.value.trim)))
                  case Some(_)        => Left(AuthError.InvalidDpopProof("more than one DPoP header"))
                  case None           => Left(AuthError.InvalidDpopProof("no DPoP proof"))
            case _ => Right(None) // another scheme (Basic, ...) carries no token
        case Some(Nil) => Right(None)

  /** The DPoP rules for a token that passed validation (RFC 9449 §7). */
  private def bind[F[_]](presented: Presented, claims: TokenClaims, req: Request[F], config: Config, replay: ReplayCache[F])(using
      F: Async[F]
  ): F[Either[AuthError, TokenClaims]] =
    (presented, claims.boundKeyThumbprint) match
      // A bound token is useless without its key: never accept it as Bearer.
      case (Presented.Bearer(_), Some(_)) => F.pure(Left(AuthError.DpopRequired))
      case (Presented.Bearer(_), None) =>
        F.pure(Either.cond(config.dpop == DpopPolicy.Allowed, claims, AuthError.DpopRequired))
      case (Presented.Dpop(_, _), None) => F.pure(Left(AuthError.InvalidDpopProof("the token is not bound to a key")))
      case (Presented.Dpop(token, proof), Some(jkt)) =>
        F.realTime.flatMap { now =>
          Dpop.verify(proof, req.method.name, config.resource.resource, Some(token), now.toSeconds, config.dpopSettings, replay).map {
            case Left(e) => Left(e)
            case Right(p) =>
              Either.cond(java.security.MessageDigest.isEqual(p.thumbprint.getBytes, jkt.getBytes), claims, AuthError.InvalidDpopProof("the proof's key is not the token's"))
          }
        }

  private def build[F[_]](validator: TokenValidator[F], config: Config, hooks: Hooks[F], optional: Boolean)(
      serverFor: (Request[F], Option[TokenClaims]) => F[Either[Response[F], ServerHandler[F]]]
  )(using F: Async[F]): HttpRoutes[F] =
    val metadataUrl = config.resourceMetadataUrl
    val mcp = McpHttp.routesFor[F](config.mcpConfig) { req =>
      val method = req.headers.get(McpHeaders.Method).map(_.head.value).getOrElse("")
      val name   = req.headers.get(McpHeaders.Name).map(_.head.value).flatMap(McpHeaders.decode)
      val target = Target.of(method, name)
      val access = config.policy.access(target)

      def record(allowed: Boolean, reason: String, detail: Option[String], caller: Option[TokenClaims]): F[Unit] =
        F.realTime.flatMap { now =>
          hooks.audit.record(
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

      def scheme(caller: Option[TokenClaims]) = if caller.exists(_.boundKeyThumbprint.isDefined) then "DPoP" else "Bearer"

      def refuse(error: AuthError): F[Either[Response[F], ServerHandler[F]]] =
        record(false, error.kind, Some(error.description), None)
          .as(
            Left(
              AuthChallenge.unauthorized[F](
                error,
                metadataUrl,
                scope = access.fold(Set.empty[String])(_.required),
                schemes = config.schemes,
                dpopAlgs = config.dpopSettings.algorithms
              )
            )
          )

      val authenticated: F[Either[AuthError, Option[TokenClaims]]] =
        presented(req) match
          case Left(error) => F.pure(Left(error))
          case Right(None) => F.pure(Right(None))
          case Right(Some(p)) =>
            validator
              .validate(p.token)
              .handleError(e => Left(AuthError.KeysUnavailable(Option(e.getMessage).getOrElse(e.toString))))
              .flatMap {
                case Right(claims) => bind(p, claims, req, config, hooks.replay).map(_.map(Some(_)))
                case Left(error)   => F.pure(Left(error))
              }

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
                .as(Left(AuthChallenge.forbidden[F](Set.empty, metadataUrl, scheme(caller))))
            case Some(a) if a.allows(caller) =>
              record(true, "ok", None, caller) *> serverFor(req, caller)
            case Some(a) =>
              val error = AuthError.InsufficientScope(a.required, claims.scopes)
              record(false, error.kind, Some(error.description), caller)
                .as(Left(AuthChallenge.forbidden[F](a.required, metadataUrl, scheme(caller))))
      }
    }
    metadataRoutes(config) <+> mcp
