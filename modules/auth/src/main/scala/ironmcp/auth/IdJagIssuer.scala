package ironmcp
package auth

import cats.Applicative
import cats.effect.Async
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.{`Cache-Control`, `Content-Type`, `WWW-Authenticate`}

import java.security.{PrivateKey, PublicKey, SecureRandom}
import scala.concurrent.duration.*

/** The enterprise IdP's half of Enterprise-Managed Authorization (`ext-auth`,
  * stable 2026-07-28): RFC 8693 token exchange from a user's ID token to an
  * Identity Assertion JWT Authorization Grant (ID-JAG) for one MCP server, if
  * the administrators' [[IdJagIssuer.Policy]] allows it.
  *
  * Few identity providers issue ID-JAGs yet (Keycloak 26.7 redeems them but
  * cannot issue them), so this runs beside one: it trusts the SSO's ID tokens
  * by its JWKS, and signs ID-JAGs with a key of its own that the MCP server's
  * authorization server is told to trust.
  *
  * What it refuses:
  *   - an unauthenticated client, or one using a method it was not registered with
  *   - an ID token that is not for the authenticated client (`aud`/`azp`), not
  *     an ID token (an access or refresh token from the same issuer), expired,
  *     or older than [[Config.maxIdTokenAge]]
  *   - an audience (authorization server) or resource no rule names; every
  *     such refusal reads the same, so a client cannot map the policy
  *   - scopes outside the rules' (narrowed; refused when nothing is left)
  *   - an authentication context (`acr`, `auth_time`) a rule asks for and the
  *     sign-in lacks: `insufficient_user_authentication` (RFC 9470), so the
  *     client can send the user back to sign in with MFA
  *   - repeated parameters (RFC 6749 §3.2), and bodies over [[Config.maxBodyBytes]]
  *
  * Every decision goes to the [[AuthAudit]] with the user, client, resource
  * and scopes, never a token.
  */
object IdJagIssuer:

  /** Who a [[Rule]] covers, from the verified ID token. */
  enum Who:
    case Anyone
    /** By `sub`. */
    case Subjects(subs: Set[String])
    /** By group membership, from a `groups`-style claim. */
    case Groups(groups: Set[String], claim: String = "groups")
    /** By verified email domain (`email` with `email_verified: true`). */
    case EmailDomains(domains: Set[String])
    case Matching(name: String, test: TokenClaims => Boolean)

    def covers(id: TokenClaims): Boolean = this match
      case Anyone           => true
      case Subjects(subs)   => id.sub.exists(subs.contains)
      case Groups(gs, name) => id.claim[List[String]](name).exists(_.exists(gs.contains))
      case EmailDomains(ds) =>
        id.claim[Boolean]("email_verified").contains(true) &&
          id.claim[String]("email").exists(e => ds.exists(d => e.toLowerCase.endsWith("@" + d.toLowerCase)))
      case Matching(_, test) => test(id)

    override def toString = this match
      case Anyone            => "anyone"
      case Subjects(s)       => s"subjects(${s.mkString(",")})"
      case Groups(g, c)      => s"$c(${g.mkString(",")})"
      case EmailDomains(d)   => s"email@(${d.mkString(",")})"
      case Matching(name, _) => name

  /** One administrator rule: these people, through this client, may reach this
    * MCP server with at most these scopes.
    *
    * @param client              the client's id here (its SSO client)
    * @param authorizationServer the MCP server's authorization server issuer: the ID-JAG's `aud`
    * @param resource            the MCP server's resource identifier
    * @param scopes              the most this rule grants
    * @param clientIdAtServer    the client's id at that authorization server, when it
    *                            differs (draft-ietf-oauth-identity-assertion-authz-grant §5)
    * @param acr                 if not empty, the sign-in's `acr` must be one of these
    * @param maxAuthAge          if set, the sign-in (`auth_time`, else `iat`) must be this recent
    */
  final case class Rule(
      client: String,
      authorizationServer: String,
      resource: String,
      who: Who,
      scopes: Set[String],
      clientIdAtServer: Option[String] = None,
      acr: Set[String] = Set.empty,
      maxAuthAge: Option[FiniteDuration] = None
  ):
    def authenticationSatisfied(id: TokenClaims, now: Long): Boolean =
      val acrOk  = acr.isEmpty || id.claim[String]("acr").exists(acr.contains)
      val authAt = id.claim[Long]("auth_time").orElse(id.iat)
      val ageOk  = maxAuthAge.forall(max => authAt.exists(t => now - t <= max.toSeconds))
      acrOk && ageOk

  /** What the client asked for, after its ID token checked out. */
  final case class Request(idToken: TokenClaims, client: String, audience: String, resource: Option[String], scopes: Set[String], now: Long)

  enum Decision:
    case Grant(resource: String, scopes: Set[String], clientIdAtServer: String)
    /** `error` is the OAuth code; `reason` goes to the audit log only. */
    case Deny(error: String, reason: String)

  trait Policy[F[_]]:
    def decide(request: Request): F[Decision]

  object Policy:
    /** Deny unless some rule covers the request. Several matching rules
      * grant the union of their scopes; the client gets the scopes it asked
      * for within that (all of it when it asked for none).
      */
    def rules[F[_]: Applicative](rules: Rule*): Policy[F] = request =>
      val forTarget = rules.filter(r =>
        r.client == request.client && r.authorizationServer == request.audience && request.resource.forall(_ == r.resource) && r.who.covers(request.idToken)
      )
      val decision = forTarget.map(_.resource).distinct.toList match
        case Nil => Decision.Deny("invalid_target", s"no rule lets ${request.idToken.sub.getOrElse("?")} reach ${request.resource.getOrElse(request.audience)} through ${request.client}")
        case _ :: _ :: _ => Decision.Deny("invalid_target", "several resources at this audience: the request must name one")
        case resource :: Nil =>
          val satisfied = forTarget.filter(_.authenticationSatisfied(request.idToken, request.now))
          if satisfied.isEmpty then Decision.Deny("insufficient_user_authentication", s"rules for $resource need ${forTarget.map(r => (r.acr, r.maxAuthAge)).mkString(" or ")}")
          else
            val allowed = satisfied.flatMap(_.scopes).toSet
            val granted = if request.scopes.isEmpty then allowed else request.scopes.intersect(allowed)
            val clientIds = satisfied.map(_.clientIdAtServer.getOrElse(request.client)).distinct
            if granted.isEmpty && request.scopes.nonEmpty then Decision.Deny("invalid_scope", s"none of ${request.scopes.mkString(" ")} is allowed")
            else if clientIds.size != 1 then Decision.Deny("server_error", s"rules disagree on the client id at the server: ${clientIds.mkString(", ")}")
            else Decision.Grant(resource, granted, clientIds.head)
      Applicative[F].pure(decision)

  /** The key ID-JAGs are signed with. Its public half (`jwks`) is what the
    * authorization servers trust.
    */
  final case class Signer(key: PrivateKey, publicKey: PublicKey, algorithm: JwsAlgorithm, keyId: String):
    require(algorithm.isAsymmetric, "an ID-JAG must be signed with a private key")
    def jwks: Either[String, JwksKeySet] = Jwk.fromPublicKey(publicKey, Some(keyId), Some(algorithm)).map(k => JwksKeySet(List(k)))
    override def toString = s"Signer(${algorithm.name}, $keyId)"

  /** @param issuer        this issuer's identifier: every ID-JAG's `iss`
    * @param tokenEndpoint where clients post (for assertion `aud` and metadata)
    * @param signer        the ID-JAG signing key
    * @param sso           the SSO whose ID tokens are accepted: its issuer and keys
    * @param clients       the clients and their credentials
    * @param policy        the administrators' rules
    * @param lifetime      how long an ID-JAG lives; the draft suggests minutes
    * @param maxIdTokenAge an ID token older than this is refused, however long it lives
    * @param claimsFrom    ID token claims copied into the ID-JAG when present
    *                      (`email` only when `email_verified`)
    * @param backdate      how far before now `iat` is set; `exp` still counts from
    *                      now. Keycloak 26.7 refuses an `iat` even a second in its
    *                      future, whatever clock skew it is configured with
    */
  final case class Config[F[_]](
      issuer: String,
      tokenEndpoint: String,
      signer: Signer,
      ssoIssuer: String,
      ssoKeys: JwksProvider[F],
      clients: ClientRegistry[F],
      policy: Policy[F],
      audit: AuthAudit[F],
      lifetime: FiniteDuration = 5.minutes,
      maxIdTokenAge: FiniteDuration = 1.hour,
      clockSkew: FiniteDuration = 60.seconds,
      claimsFrom: List[String] = List("acr", "amr", "auth_time", "email"),
      maxBodyBytes: Int = 64 * 1024,
      backdate: FiniteDuration = 5.seconds
  )

  /** OIDC ID tokens, and nothing else from the same issuer: no `at+jwt` or
    * ID-JAG header, payload `typ` absent or `ID` (Keycloak marks its access
    * tokens `Bearer` and refresh tokens `Refresh`), no scopes.
    */
  val idTokenProfile: TokenValidator.AccessTokenProfile = TokenValidator.AccessTokenProfile(
    "oidc-id-token",
    (parts, payload) =>
      val c = payload.hcursor
      for
        _ <- Either.cond(!parts.typ.exists(t => t.equalsIgnoreCase("at+jwt") || t.equalsIgnoreCase(EnterpriseAuth.IdJagTyp)), (), s"header typ ${parts.typ.getOrElse("")} is not an ID token's")
        _ <- Either.cond(c.get[String]("typ").toOption.forall(_ == "ID"), (), s"payload typ ${c.get[String]("typ").getOrElse("")} is not ID")
        _ <- Either.cond(c.downField("scope").failed && c.downField("scp").failed, (), "an ID token carries no scopes")
      yield ()
  )

  /** Successful exchanges and OAuth errors (RFC 6749 §5.2). */
  enum Outcome:
    case Issued(response: TokenResponse)
    case Refused(status: Status, error: String, detail: String)

  /** The exchange, without HTTP: `form` is the decoded body. */
  def exchange[F[_]: Async](config: Config[F], form: Map[String, String], headers: Headers): F[Outcome] =
    def refuse(error: String, detail: String, status: Status = Status.BadRequest): F[Outcome] =
      Async[F].pure(Outcome.Refused(status, error, detail))

    def audit(now: Long, ok: Boolean, reason: String, detail: Option[String], client: Option[String], id: Option[TokenClaims], resource: Option[String], requested: Set[String], granted: Set[String], jti: Option[String]) =
      config.audit.record(
        AuthEvent(now, ok, reason, detail, "token-exchange", s"resource:${resource.getOrElse("-")}", id.flatMap(_.sub), client, Some(config.ssoIssuer), jti, None, requested, granted)
      )

    val requested = form.get("scope").fold(Set.empty[String])(_.split(' ').filter(_.nonEmpty).toSet)
    Async[F].realTime.map(_.toSeconds).flatMap { now =>
      val shape =
        if !form.get("grant_type").contains(EnterpriseAuth.TokenExchange) then Some("unsupported_grant_type" -> s"grant_type ${form.getOrElse("grant_type", "")}")
        else if !form.get("requested_token_type").contains(EnterpriseAuth.IdJagType) then Some("invalid_request" -> "requested_token_type is not id-jag")
        else if !form.get("subject_token_type").contains(EnterpriseAuth.Subject.IdTokenType) then Some("invalid_request" -> "subject_token_type is not id_token")
        else if form.get("subject_token").forall(_.isEmpty) then Some("invalid_request" -> "no subject_token")
        else if form.get("audience").forall(_.isEmpty) then Some("invalid_request" -> "no audience")
        else if form.contains("actor_token") then Some("invalid_request" -> "delegation (actor_token) is not supported")
        else None
      config.clients.authenticate(form, headers).flatMap {
        case Left(failure) =>
          val status = if failure.error == "invalid_client" then Status.Unauthorized else Status.BadRequest
          audit(now, false, failure.error, Some(failure.detail), None, None, form.get("resource"), requested, Set.empty, None) *> refuse(failure.error, failure.detail, status)
        case Right(client) =>
          shape match
            case Some((error, detail)) =>
              audit(now, false, error, Some(detail), Some(client), None, form.get("resource"), requested, Set.empty, None) *> refuse(error, detail)
            case None =>
              val validator = TokenValidator.jwks[F](
                config.ssoKeys,
                TokenValidator.Config(config.ssoIssuer, client, profile = idTokenProfile, clockSkew = config.clockSkew, maxAge = Some(config.maxIdTokenAge))
              )
              validator.validate(form("subject_token")).flatMap {
                case Left(e) =>
                  audit(now, false, "invalid_grant", Some(s"subject token: ${e.description}"), Some(client), None, form.get("resource"), requested, Set.empty, None) *>
                    refuse("invalid_grant", e.description)
                case Right(id) if id.aud.size > 1 && !id.claim[String]("azp").contains(client) =>
                  audit(now, false, "invalid_grant", Some("subject token: several audiences and azp is not the client"), Some(client), Some(id), form.get("resource"), requested, Set.empty, None) *>
                    refuse("invalid_grant", "azp")
                case Right(id) if id.sub.isEmpty =>
                  refuse("invalid_grant", "the ID token has no sub")
                case Right(id) =>
                  config.policy.decide(Request(id, client, form("audience"), form.get("resource"), requested, now)).flatMap {
                    case Decision.Deny(error, reason) =>
                      audit(now, false, error, Some(reason), Some(client), Some(id), form.get("resource"), requested, Set.empty, None) *> refuse(error, reason)
                    case Decision.Grant(resource, scopes, clientIdAtServer) =>
                      mint(config, id, form("audience"), resource, scopes, clientIdAtServer, now).flatMap { case (jag, jti) =>
                        audit(now, true, "ok", None, Some(client), Some(id), Some(resource), requested, scopes, Some(jti)).as(
                          Outcome.Issued(
                            TokenResponse(
                              access_token = jag,
                              token_type = "N_A",
                              expires_in = Some(config.lifetime.toSeconds),
                              scope = Option.when(scopes.nonEmpty)(scopes.toList.sorted.mkString(" ")),
                              issued_token_type = Some(EnterpriseAuth.IdJagType)
                            )
                          )
                        )
                      }
                  }
              }
      }
    }

  private val random = new SecureRandom()

  private def mint[F[_]: Async](config: Config[F], id: TokenClaims, audience: String, resource: String, scopes: Set[String], clientId: String, now: Long): F[(String, String)] =
    Async[F].delay {
      val bytes = new Array[Byte](18)
      random.nextBytes(bytes)
      val jti = Jwt.base64UrlEncode(bytes)
      val copied = config.claimsFrom.flatMap { name =>
        if name == "email" && !id.claim[Boolean]("email_verified").contains(true) then None
        else id.rawJson.hcursor.downField(name).focus.filterNot(_.isNull).map(name -> _)
      }
      val claims = Json.fromFields(
        List(
          "iss"       -> config.issuer.asJson,
          "sub"       -> id.sub.asJson,
          "aud"       -> audience.asJson,
          "client_id" -> clientId.asJson,
          "resource"  -> resource.asJson,
          "jti"       -> jti.asJson,
          "iat"       -> (now - config.backdate.toSeconds).asJson,
          "exp"       -> (now + config.lifetime.toSeconds).asJson
        ) ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" ").asJson) ++ copied
      )
      val header = JsonObject("typ" -> EnterpriseAuth.IdJagTyp.asJson, "kid" -> config.signer.keyId.asJson)
      (Jwt.sign(config.signer.algorithm, config.signer.key, header, claims), jti)
    }

  /** RFC 8414 metadata for this issuer. */
  def metadata[F[_]](config: Config[F], jwksUri: String): Json =
    Json.obj(
      "issuer"                                           -> config.issuer.asJson,
      "token_endpoint"                                   -> config.tokenEndpoint.asJson,
      "jwks_uri"                                         -> jwksUri.asJson,
      "grant_types_supported"                            -> List(EnterpriseAuth.TokenExchange).asJson,
      "token_endpoint_auth_methods_supported"            -> config.clients.methods.asJson,
      "token_endpoint_auth_signing_alg_values_supported" -> JwsAlgorithm.asymmetric.map(_.name).toList.sorted.asJson
    )

  /** `POST tokenPath` (the exchange), `GET jwksPath` (the signing key), and
    * RFC 8414 metadata at `/.well-known/oauth-authorization-server`.
    */
  def routes[F[_]: Async](config: Config[F], tokenPath: String = "/token", jwksPath: String = "/jwks"): HttpRoutes[F] =
    val noStore = Headers(`Cache-Control`(CacheDirective.`no-store`), Header.Raw(org.typelevel.ci.CIString("Pragma"), "no-cache"))
    def json(status: Status, body: Json) =
      Response[F](status).withEntity(body).withContentType(`Content-Type`(MediaType.application.json)).putHeaders(noStore)
    val jwksUri = config.tokenEndpoint.stripSuffix(tokenPath) + jwksPath
    HttpRoutes.of[F] {
      case req if req.method == Method.POST && req.uri.path.renderString == tokenPath =>
        val isForm = req.contentType.exists(_.mediaType == MediaType.application.`x-www-form-urlencoded`)
        if !isForm then Async[F].pure(json(Status.BadRequest, Json.obj("error" -> "invalid_request".asJson)))
        else
          req.body.take(config.maxBodyBytes.toLong + 1).compile.to(Array).flatMap { bytes =>
            val body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            UrlForm.decodeString(Charset.`UTF-8`)(body) match
              case _ if bytes.length > config.maxBodyBytes => Async[F].pure(json(Status.PayloadTooLarge, Json.obj("error" -> "invalid_request".asJson)))
              case Left(_) => Async[F].pure(json(Status.BadRequest, Json.obj("error" -> "invalid_request".asJson)))
              case Right(form) if form.values.exists(_._2.size > 1) =>
                Async[F].pure(json(Status.BadRequest, Json.obj("error" -> "invalid_request".asJson, "error_description" -> "a parameter appears more than once".asJson)))
              case Right(form) =>
                exchange(config, form.values.view.mapValues(_.headOption.getOrElse("")).toMap, req.headers).map {
                  case Outcome.Issued(token) => json(Status.Ok, token.asJson)
                  case Outcome.Refused(status, error, _) =>
                    val response = json(status, Json.obj("error" -> error.asJson, "error_description" -> publicDescription(error).asJson))
                    if status == Status.Unauthorized && req.headers.get[headers.Authorization].isDefined then
                      response.putHeaders(`WWW-Authenticate`(Challenge("Basic", config.issuer)))
                    else response
                }
          }
      case req if req.method == Method.GET && req.uri.path.renderString == jwksPath =>
        Async[F].pure(config.signer.jwks.fold(e => json(Status.InternalServerError, Json.obj("error" -> e.asJson)), k => json(Status.Ok, k.asJson).putHeaders(`Cache-Control`(CacheDirective.`max-age`(5.minutes)))))
      case req if req.method == Method.GET && req.uri.path.renderString == "/.well-known/oauth-authorization-server" =>
        Async[F].pure(json(Status.Ok, metadata(config, jwksUri)))
    }

  /** One fixed sentence per error: nothing about the policy, the user or the token. */
  private def publicDescription(error: String): String = error match
    case "invalid_client"                   => "Client authentication failed."
    case "invalid_grant"                    => "The subject token is not acceptable."
    case "invalid_target"                   => "This client may not obtain a grant for that audience or resource for this user."
    case "invalid_scope"                    => "None of the requested scopes may be granted."
    case "insufficient_user_authentication" => "Sign in again with a stronger method."
    case "unsupported_grant_type"           => "Only token exchange for an ID-JAG is supported."
    case _                                  => "The request is not a valid ID-JAG token exchange."
