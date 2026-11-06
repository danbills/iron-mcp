package ironmcp
package auth

import cats.effect.Async
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.{`Cache-Control`, `Content-Type`}

import scala.concurrent.duration.*

/** The OAuth Client Credentials extension (`ext-auth`, stable 2026-07-28):
  * a machine client, registered out of band, gets its own tokens.
  *
  * The extension recommends JWT client authentication ([[ClientAuth.PrivateKeyJwt]],
  * RFC 7523 §2.2) over a secret, and requires the authorization server to
  * list the method; both are checked before anything is sent.
  */
object ClientCredentials:

  /** `scopes => token` for [[StepUpClient.create]]: machine clients get the
    * Scope-Union Rule, proactive renewal and DPoP binding like any other.
    */
  def authorizer[F[_]: Async](
      http: Client[F],
      server: AuthorizationServerMetadata,
      client: ClientAuth,
      resource: String,
      dpop: Option[Dpop.Key] = None
  ): Either[OAuthError, Set[String] => F[TokenResponse]] =
    for
      _ <- ResourceIdentity.requireSecure(server.token_endpoint).leftMap(OAuthError.Insecure(_))
      _ <- Either.cond(server.grant_types_supported.forall(_.contains("client_credentials")), (), OAuthError.GrantUnsupported(server.issuer, "client_credentials"))
      _ <- ClientAuth.check(client, server.token_endpoint_auth_methods_supported)
      _ <- client match
        case jwt: ClientAuth.PrivateKeyJwt =>
          Either.cond(
            server.token_endpoint_auth_signing_alg_values_supported.forall(_.contains(jwt.algorithm.name)),
            (),
            OAuthError.ClientAuthUnsupported(s"private_key_jwt with ${jwt.algorithm.name}", server.token_endpoint_auth_signing_alg_values_supported.getOrElse(Nil))
          )
        case _ => Right(())
    yield (scopes: Set[String]) =>
      OAuthEndpoint
        .post[F, TokenResponse](
          http,
          server.issuer,
          server.token_endpoint,
          client,
          List("grant_type" -> "client_credentials", "resource" -> resource) ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" ")),
          dpop = dpop
        )
        .flatTap(t => OAuthClient.checkTokenType(t).liftTo[F])

/** Enterprise-Managed Authorization (`ext-auth`, stable 2026-07-28): the
  * user signs in once to the company's identity provider, and the IdP, by
  * its administrators' policy, decides which MCP servers each user and client
  * may reach.
  *
  *   1. **Token exchange at the IdP** (RFC 8693): the user's ID token (or a
  *      refresh token, after SAML sign-in) for an Identity Assertion JWT
  *      Authorization Grant (ID-JAG), addressed to the MCP server's
  *      authorization server and naming the MCP resource.
  *   2. **JWT-bearer grant at that authorization server** (RFC 7523): the
  *      ID-JAG for an access token bound to the MCP resource.
  *
  * The MCP server itself sees an ordinary access token and checks it as
  * always; nothing changes on the resource server.
  */
object EnterpriseAuth:

  val TokenExchange = "urn:ietf:params:oauth:grant-type:token-exchange"
  val JwtBearer     = "urn:ietf:params:oauth:grant-type:jwt-bearer"
  val IdJagType     = "urn:ietf:params:oauth:token-type:id-jag"
  val IdJagProfile  = "urn:ietf:params:oauth:grant-profile:id-jag"
  val IdJagTyp      = "oauth-id-jag+jwt"

  /** What the client presents to the IdP. */
  enum Subject(val token: String, val tokenType: String):
    case IdToken(value: String) extends Subject(value, "urn:ietf:params:oauth:token-type:id_token")
    case RefreshToken(value: String) extends Subject(value, "urn:ietf:params:oauth:token-type:refresh_token")

  /** @param idpIssuer        the enterprise IdP's issuer
    * @param idpTokenEndpoint where it does token exchange
    * @param idpClient        how the client authenticates there (as for its SSO)
    * @param authorizationServer the MCP server's authorization server (from discovery)
    * @param asClient         how the client authenticates there; a Client ID
    *                         Metadata Document URL with `Public` when not pre-registered
    * @param resource         the MCP server's resource identifier
    * @param requireProfile   refuse an authorization server that does not
    *                         advertise the ID-JAG grant profile
    */
  final case class Config(
      idpIssuer: String,
      idpTokenEndpoint: String,
      idpClient: ClientAuth,
      authorizationServer: AuthorizationServerMetadata,
      asClient: ClientAuth,
      resource: String,
      requireProfile: Boolean = true
  )

  /** Step 1: an ID-JAG from the IdP. */
  def requestIdJag[F[_]: Async](http: Client[F], config: Config, subject: Subject, scopes: Set[String]): F[TokenResponse] =
    OAuthEndpoint
      .post[F, TokenResponse](
        http,
        config.idpIssuer,
        config.idpTokenEndpoint,
        config.idpClient,
        List(
          "grant_type"           -> TokenExchange,
          "requested_token_type" -> IdJagType,
          "audience"             -> config.authorizationServer.issuer,
          "resource"             -> config.resource,
          "subject_token"        -> subject.token,
          "subject_token_type"   -> subject.tokenType
        ) ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" "))
      )
      .flatTap { r =>
        Async[F].raiseWhen(!r.issued_token_type.contains(IdJagType))(
          OAuthError.UnexpectedTokenType(r.issued_token_type.getOrElse("(no issued_token_type)"))
        )
      }

  /** Step 2: the ID-JAG for an access token at the MCP server's authorization server. */
  def redeem[F[_]: Async](http: Client[F], config: Config, idJag: String, dpop: Option[Dpop.Key] = None): F[TokenResponse] =
    val server = config.authorizationServer
    for
      _ <- Async[F].raiseWhen(config.requireProfile && !server.authorization_grant_profiles_supported.exists(_.contains(IdJagProfile)))(
        OAuthError.GrantUnsupported(server.issuer, IdJagProfile)
      )
      _ <- ClientAuth.check(config.asClient, server.token_endpoint_auth_methods_supported).liftTo[F]
      tokens <- OAuthEndpoint.post[F, TokenResponse](
        http,
        server.issuer,
        server.token_endpoint,
        config.asClient,
        List("grant_type" -> JwtBearer, "assertion" -> idJag),
        dpop = dpop
      )
      _ <- OAuthClient.checkTokenType(tokens).liftTo[F]
    yield tokens

  /** Both steps as [[StepUpClient]]'s `authorize`. `subject` is asked for on
    * each authorization, so it can hand out a fresh ID token.
    */
  def authorizer[F[_]: Async](http: Client[F], config: Config, subject: F[Subject], dpop: Option[Dpop.Key] = None): Set[String] => F[TokenResponse] =
    scopes =>
      for
        s     <- subject
        jag   <- requestIdJag(http, config, s, scopes)
        token <- redeem(http, config, jag.access_token, dpop)
      yield token

  /** The checks an authorization server makes on an ID-JAG before it issues
    * an access token (draft-ietf-oauth-identity-assertion-authz-grant §4.4.1,
    * and the MCP profile's `resource` rule): for a Scala authorization server,
    * and for tests.
    *
    * @param asIssuer       this authorization server's issuer: the ID-JAG's `aud`
    * @param trustedIdps    the IdP issuers it accepts grants from, each with its keys
    * @param clientId       the client that authenticated on the jwt-bearer request
    * @param resources      the resources this server issues tokens for
    */
  def verifyIdJag[F[_]: Async](
      assertion: String,
      asIssuer: String,
      trustedIdps: Map[String, JwksProvider[F]],
      clientId: String,
      resources: Set[String],
      replay: ReplayCache[F],
      clockSkew: FiniteDuration = 60.seconds
  ): F[Either[AuthError, TokenClaims]] =
    Jwt.parse(assertion) match
      case Left(e) => e.asLeft[TokenClaims].pure[F]
      case Right(parts) =>
        val iss = parts.payload.hcursor.get[String]("iss").toOption
        (iss.flatMap(trustedIdps.get), Jwt.algorithm(parts, JwsAlgorithm.asymmetric)) match
          case (None, _)        => AuthError.IssuerMismatch("a trusted IdP", iss.getOrElse("")).asLeft[TokenClaims].pure[F]
          case (_, Left(e))     => e.asLeft[TokenClaims].pure[F]
          case (Some(keys), Right(alg)) =>
            keys.select(parts.keyId, alg).flatMap {
              case Left(e) => e.asLeft[TokenClaims].pure[F]
              case Right(key) =>
                Async[F].realTime.map(_.toSeconds).flatMap { now =>
                  val profile = TokenValidator.AccessTokenProfile("id-jag", (p, _) => Either.cond(p.typ.contains(IdJagTyp), (), s"typ is not $IdJagTyp"))
                  val config  = TokenValidator.Config(iss.get, asIssuer, profile = profile, clockSkew = clockSkew)
                  val checked = for
                    _      <- Jwt.verify(parts, key.key, Set(alg))
                    claims <- TokenValidator.checkClaims(parts, now, config)
                    _      <- Either.cond(claims.clientId.contains(clientId), (), AuthError.WrongAudience(s"client_id $clientId", claims.clientId.toList))
                    _      <- claims.claim[String]("resource").traverse_(r => Either.cond(resources.contains(r), (), AuthError.WrongAudience(resources.mkString(" "), List(r))))
                    _      <- Either.cond(claims.sub.isDefined, (), AuthError.MissingClaim("sub"))
                    jti    <- claims.jti.toRight(AuthError.MissingClaim("jti"))
                  yield (claims, jti)
                  checked match
                    case Left(e) => e.asLeft[TokenClaims].pure[F]
                    case Right((claims, jti)) =>
                      replay.firstUse(s"id-jag:${claims.iss}:$jti", claims.exp + clockSkew.toSeconds).map(first => Either.cond(first, claims, AuthError.Revoked("the ID-JAG was already used")))
                }
            }

/** A Client ID Metadata Document: a client's registration, published at an
  * `https` URL that is itself the `client_id`. An authorization server that
  * supports them (`client_id_metadata_document_supported`) fetches it, so
  * the client needs no registration step and no shared secret.
  */
final case class ClientMetadataDocument(
    client_id: String,
    client_name: String,
    redirect_uris: List[String],
    grant_types: List[String] = List("authorization_code", "refresh_token"),
    response_types: List[String] = List("code"),
    token_endpoint_auth_method: String = "none",
    jwks_uri: Option[String] = None,
    jwks: Option[Json] = None,
    client_uri: Option[String] = None,
    logo_uri: Option[String] = None,
    scope: Option[String] = None,
    dpop_bound_access_tokens: Option[Boolean] = None
):
  /** The rules a server will check, checked here first. */
  def validate: Either[String, ClientMetadataDocument] =
    for
      _ <- ResourceIdentity.requireSecure(client_id).filterOrElse(_ => client_id.startsWith("https://"), "client_id must be an https URL")
      _ <- Either.cond(new java.net.URI(client_id).getRawPath.length > 1, (), "client_id must have a path")
      _ <- Either.cond(redirect_uris.nonEmpty, (), "at least one redirect URI")
      _ <- Either.cond(!List("client_secret_post", "client_secret_basic", "client_secret_jwt").contains(token_endpoint_auth_method), (), "a published document cannot use a shared secret")
      _ <- Either.cond(token_endpoint_auth_method != "private_key_jwt" || jwks.isDefined || jwks_uri.isDefined, (), "private_key_jwt needs jwks or jwks_uri")
    yield this

object ClientMetadataDocument:
  given Codec[ClientMetadataDocument] =
    val derived = Codec.AsObject.derived[ClientMetadataDocument]
    Codec.from(derived, derived.mapJson(_.dropNullValues))

  /** Serves `doc` at the path of its own `client_id`. */
  def routes[F[_]: Async](doc: ClientMetadataDocument): Either[String, HttpRoutes[F]] =
    doc.validate.map { valid =>
      val path = new java.net.URI(valid.client_id).getRawPath
      HttpRoutes.of[F] {
        case req if req.method == Method.GET && req.uri.path.renderString == path =>
          Async[F].pure(
            Response[F](Status.Ok)
              .withEntity(valid.asJson)
              .withContentType(`Content-Type`(MediaType.application.json))
              .putHeaders(`Cache-Control`(CacheDirective.`max-age`(1.hour)))
          )
      }
    }
