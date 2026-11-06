package ironmcp
package auth

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

/** The two `ext-auth` extensions end to end, against an in-memory IdP and
  * authorization server, with the MCP server checking the result as usual.
  */
class EnterpriseSuite extends CatsEffectSuite:
  import AuthFixtures.*

  private val idpIssuer = "http://idp.localhost"
  private val asIssuer  = issuer // the MCP server's authorization server, AuthFixtures.issuer
  private val idpKey    = AuthFixtures.ed
  private val asKey     = AuthFixtures.rsa
  private val machine   = AuthFixtures.ec

  private val asMetadata = AuthorizationServerMetadata(
    issuer = asIssuer,
    token_endpoint = "http://as.localhost/token",
    grant_types_supported = Some(List("client_credentials", EnterpriseAuth.JwtBearer)),
    token_endpoint_auth_methods_supported = Some(List("private_key_jwt", "client_secret_basic")),
    token_endpoint_auth_signing_alg_values_supported = Some(List("ES256")),
    authorization_grant_profiles_supported = Some(List(EnterpriseAuth.IdJagProfile))
  )

  /** Access tokens as the authorization server mints them: RS256, at+jwt, bound to the MCP resource. */
  private def mint(sub: String, clientId: String, scope: String) =
    rs(claims(scope = scope, sub = sub, iss = asIssuer, extra = List("client_id" -> clientId.asJson, "jti" -> java.util.UUID.randomUUID.toString.asJson)))

  private val resourceValidator = jwksValidator(vkey(asKey, "k1"))

  private def form(req: Request[IO]) = req.as[UrlForm].map(f => (k: String) => f.getFirst(k))

  private def oauthError(code: String) = BadRequest(Json.obj("error" -> code.asJson))

  /** The IdP: token exchange from an ID token it issued, to an ID-JAG for the
    * MCP server's authorization server, if its policy lets this user and
    * client reach that resource.
    */
  private def idp(allowed: Set[(String, String)]) = HttpRoutes.of[IO] {
    case req @ POST -> Root / "token" =>
      form(req).flatMap { f =>
        val clientOk = f("client_id").contains("desktop") && f("client_secret").contains("idp-secret")
        val user     = f("subject_token").map(_.stripPrefix("id-token-for-"))
        if !clientOk then oauthError("invalid_client")
        else if f("grant_type") != Some(EnterpriseAuth.TokenExchange) || f("requested_token_type") != Some(EnterpriseAuth.IdJagType) then oauthError("unsupported_grant_type")
        else if f("audience") != Some(asIssuer) then oauthError("invalid_target")
        else
          user.filter(u => f("resource").exists(r => allowed.contains((u, r)))) match
            case None => oauthError("access_denied")
            case Some(u) =>
              val jag = Jwt.sign(
                JwsAlgorithm.EdDSA,
                idpKey.getPrivate,
                JsonObject("typ" -> EnterpriseAuth.IdJagTyp.asJson, "kid" -> "idp".asJson),
                Json.obj(
                  "iss"       -> idpIssuer.asJson,
                  "sub"       -> u.asJson,
                  "aud"       -> asIssuer.asJson,
                  "resource"  -> f("resource").asJson,
                  "client_id" -> "desktop".asJson,
                  "jti"       -> java.util.UUID.randomUUID.toString.asJson,
                  "iat"       -> now.asJson,
                  "exp"       -> (now + 300).asJson,
                  "scope"     -> f("scope").asJson
                )
              )
              Ok(Json.obj("issued_token_type" -> EnterpriseAuth.IdJagType.asJson, "access_token" -> jag.asJson, "token_type" -> "N_A".asJson, "expires_in" -> 300.asJson))
      }
  }

  /** The MCP server's authorization server: jwt-bearer for ID-JAGs, and
    * client_credentials with a private_key_jwt assertion.
    */
  private def as(replay: ReplayCache[IO]) = HttpRoutes.of[IO] {
    case req @ POST -> Root / "token" =>
      form(req).flatMap { f =>
        f("grant_type") match
          case Some(EnterpriseAuth.JwtBearer) =>
            val trusted = Map(idpIssuer -> JwksProvider.static[IO](KeySet.of(vkey(idpKey, "idp"))))
            val basic = req.headers.get[org.http4s.headers.Authorization].collect { case org.http4s.headers.Authorization(BasicCredentials(u, _)) => u }
            EnterpriseAuth.verifyIdJag[IO](f("assertion").getOrElse(""), asIssuer, trusted, basic.getOrElse(""), Set(audience), replay).flatMap {
              case Left(e)  => oauthError(s"invalid_grant: ${e.kind}")
              case Right(c) => Ok(TokenResponse(mint(c.sub.get, c.clientId.get, c.scope.getOrElse("")), "Bearer", Some(300), scope = c.scope).asJson)
            }
          case Some("client_credentials") =>
            val assertion = f("client_assertion").getOrElse("")
            val check = for
              parts <- Jwt.parse(assertion)
              _     <- Jwt.verify(parts, machine.getPublic, Set(JwsAlgorithm.ES256))
              c      = parts.payload.hcursor
              _     <- Either.cond(c.get[String]("iss").toOption.contains("robot") && c.get[String]("sub").toOption.contains("robot"), (), AuthError.MalformedToken("iss/sub"))
              _     <- Either.cond(c.get[String]("aud").toOption.contains(asIssuer), (), AuthError.WrongAudience(asIssuer, Nil))
              _     <- Either.cond(f("client_assertion_type").contains("urn:ietf:params:oauth:client-assertion-type:jwt-bearer"), (), AuthError.MalformedToken("type"))
              _     <- Either.cond(f("resource").contains(audience), (), AuthError.WrongAudience(audience, Nil))
            yield c.get[String]("jti").toOption.get
            check match
              case Left(_) => oauthError("invalid_client")
              case Right(jti) =>
                replay.firstUse(s"assertion:$jti", now + 120).flatMap {
                  case false => oauthError("invalid_client")
                  case true  => Ok(TokenResponse(mint("robot", "robot", f("scope").getOrElse("")), "Bearer", Some(300), scope = f("scope")).asJson)
                }
          case _ => oauthError("unsupported_grant_type")
      }
  }

  private def world(allowed: Set[(String, String)] = Set(("alice", audience))): IO[Client[IO]] =
    ReplayCache.inMemory[IO]().map { replay =>
      Client.fromHttpApp(HttpApp[IO] { req =>
        val routes = req.uri.host.map(_.value) match
          case Some("idp.localhost") => idp(allowed)
          case _                     => as(replay)
        routes.orNotFound.run(req)
      })
    }

  private val config = EnterpriseAuth.Config(
    idpIssuer = idpIssuer,
    idpTokenEndpoint = "http://idp.localhost/token",
    idpClient = ClientAuth.SecretPost("desktop", "idp-secret"),
    authorizationServer = asMetadata,
    asClient = ClientAuth.SecretBasic("desktop", "as-secret"),
    resource = audience
  )

  test("enterprise-managed: ID token → ID-JAG at the IdP → access token the MCP server accepts"):
    for
      http   <- world()
      token  <- EnterpriseAuth.authorizer[IO](http, config, IO.pure(EnterpriseAuth.Subject.IdToken("id-token-for-alice")))(Set("mcp:read"))
      claims <- resourceValidator.validate(token.access_token)
    yield
      assertEquals(claims.map(_.sub), Right(Some("alice")))
      assertEquals(claims.map(_.scopes), Right(Set("mcp:read")))

  test("enterprise-managed: the IdP's policy decides; a user it does not allow gets nothing"):
    for
      http   <- world()
      result <- EnterpriseAuth.authorizer[IO](http, config, IO.pure(EnterpriseAuth.Subject.IdToken("id-token-for-mallory")))(Set("mcp:read")).attempt
    yield assert(result.left.exists { case OAuthError.TokenEndpoint(_, "access_denied", _) => true; case _ => false }, result.toString)

  test("enterprise-managed: an ID-JAG cannot be redeemed twice, or by another client"):
    for
      http  <- world()
      jag   <- EnterpriseAuth.requestIdJag[IO](http, config, EnterpriseAuth.Subject.IdToken("id-token-for-alice"), Set("mcp:read"))
      first <- EnterpriseAuth.redeem[IO](http, config, jag.access_token).attempt
      again <- EnterpriseAuth.redeem[IO](http, config, jag.access_token).attempt
      jag2  <- EnterpriseAuth.requestIdJag[IO](http, config, EnterpriseAuth.Subject.IdToken("id-token-for-alice"), Set("mcp:read"))
      other <- EnterpriseAuth.redeem[IO](http, config.copy(asClient = ClientAuth.SecretBasic("intruder", "x")), jag2.access_token).attempt
    yield
      assert(first.isRight, first.toString)
      assert(again.isLeft)
      assert(other.isLeft)

  test("enterprise-managed: an authorization server without the ID-JAG profile is refused"):
    for
      http   <- world()
      result <- EnterpriseAuth.redeem[IO](http, config.copy(authorizationServer = asMetadata.copy(authorization_grant_profiles_supported = None)), "x").attempt
    yield assert(result.left.exists(_.isInstanceOf[OAuthError.GrantUnsupported]), result.toString)

  test("client credentials with private_key_jwt: a signed assertion, never a secret; step-up works for machines"):
    for
      http      <- world()
      authorize <- IO.fromEither(ClientCredentials.authorizer[IO](http, asMetadata, ClientAuth.PrivateKeyJwt("robot", machine.getPrivate), audience))
      stepUp    <- StepUpClient.create[IO](authorize)
      first     <- stepUp.initialAuthorize(Set("mcp:read"))
      c1        <- resourceValidator.validate(first.access_token)
      _         <- stepUp.executeWithRetry(
                     IO.raiseError[Unit](ironmcp.client.McpClientError.Http(403, List("""Bearer error="insufficient_scope", scope="mcp:read mcp:export""""), "")),
                     "export",
                     retriesLeft = 1
                   ).attempt
      state     <- stepUp.state
    yield
      assertEquals(c1.map(_.sub), Right(Some("robot")))
      assertEquals(state.grantedScopes, Set("mcp:read", "mcp:export"))

  test("client credentials: a method or algorithm the server does not list is refused up front"):
    val hmacOnly = asMetadata.copy(token_endpoint_auth_methods_supported = Some(List("client_secret_basic")))
    val rsOnly   = asMetadata.copy(token_endpoint_auth_signing_alg_values_supported = Some(List("RS256")))
    for
      http <- world()
    yield
      assert(ClientCredentials.authorizer[IO](http, hmacOnly, ClientAuth.PrivateKeyJwt("robot", machine.getPrivate), audience).isLeft)
      assert(ClientCredentials.authorizer[IO](http, rsOnly, ClientAuth.PrivateKeyJwt("robot", machine.getPrivate), audience).isLeft)
      assert(ClientCredentials.authorizer[IO](http, asMetadata, ClientAuth.SecretPost("robot", "s"), audience).isLeft)
