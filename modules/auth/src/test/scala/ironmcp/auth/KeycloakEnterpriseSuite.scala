package ironmcp
package auth

import cats.effect.{IO, Ref, Resource}
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

import java.nio.file.Paths
import scala.concurrent.duration.*

/** Both `ext-auth` extensions against a live Keycloak (26.7, started with
  * `--features=identity-assertion-jwt`), set up by `scripts/keycloak-realm.sh`
  * and `scripts/keycloak-enterprise.sh`:
  *
  *   - Enterprise-Managed Authorization: a user signs in to the corp realm
  *     (the company's SSO); an in-process [[IdJagIssuer]] turns the ID token
  *     into an ID-JAG by policy; Keycloak's iron-mcp realm redeems it for an
  *     access token the MCP server accepts
  *   - Client Credentials with `private_key_jwt` and a DPoP-bound token
  *
  * Skipped, and reported as skipped, unless the env lines both scripts print
  * are set, plus `IRON_MCP_KC_IDJAG_KEY` and `IRON_MCP_KC_MACHINE_KEY` (PEM
  * private keys whose public halves the scripts registered).
  */
class KeycloakEnterpriseSuite extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val names = List(
    "IRON_MCP_KC_REALM", "IRON_MCP_KC_RESOURCE", "IRON_MCP_KC_CORP_REALM", "IRON_MCP_KC_CORP_CLIENT", "IRON_MCP_KC_CORP_SECRET",
    "IRON_MCP_KC_CORP_ADA_PASSWORD", "IRON_MCP_KC_CORP_GRACE_PASSWORD", "IRON_MCP_KC_ENTERPRISE_CLIENT", "IRON_MCP_KC_ENTERPRISE_SECRET",
    "IRON_MCP_KC_IDJAG_ISSUER", "IRON_MCP_KC_IDJAG_KEY", "IRON_MCP_KC_MACHINE_KEY"
  )
  private val env = names.map(k => k -> sys.env.get(k)).toMap
  private def setting(k: String) = env(k).get
  private def requireEnv() = assume(env.values.forall(_.isDefined), s"set ${env.collect { case (k, None) => k }.mkString(", ")} to run against Keycloak")

  private def key(name: String) = Pem.privateKeyFile(Paths.get(setting(name))).fold(e => fail(e), identity)
  private def publicOf(name: String) = Pem.publicKeyFile(Paths.get(setting(name).replace("-key.pem", "-pub.pem"))).fold(e => fail(e), identity)

  private def json(http: Client[IO], url: String) = http.expect[Json](url)

  /** The corp SSO sign-in (password grant: test realm only), returning the ID token. */
  private def signIn(http: Client[IO], user: String, password: String): IO[String] =
    json(http, s"${setting("IRON_MCP_KC_CORP_REALM")}/.well-known/openid-configuration").flatMap { oidc =>
      val form = UrlForm(
        "grant_type" -> "password", "client_id" -> setting("IRON_MCP_KC_CORP_CLIENT"), "client_secret" -> setting("IRON_MCP_KC_CORP_SECRET"),
        "username" -> user, "password" -> password, "scope" -> "openid"
      )
      http.expect[Json](Request[IO](Method.POST, Uri.unsafeFromString(oidc.hcursor.get[String]("token_endpoint").toOption.get)).withEntity(form))
        .map(_.hcursor.get[String]("id_token").toOption.get)
    }

  private final case class World(
      http: Client[IO],
      as: AuthorizationServerMetadata,
      config: EnterpriseAuth.Config,
      validator: TokenValidator[IO],
      audit: Ref[IO, List[AuthEvent]]
  )

  /** Real Keycloak for everything but the IdP's host, which is the in-process issuer. */
  private def world: Resource[IO, World] =
    EmberClientBuilder.default[IO].build.evalMap { ember =>
      val issuerUrl = setting("IRON_MCP_KC_IDJAG_ISSUER")
      val resource  = setting("IRON_MCP_KC_RESOURCE")
      for
        as      <- json(ember, s"${setting("IRON_MCP_KC_REALM")}/.well-known/oauth-authorization-server").map(_.as[AuthorizationServerMetadata].toOption.get)
        corp    <- json(ember, s"${setting("IRON_MCP_KC_CORP_REALM")}/.well-known/openid-configuration")
        corpKeys <- JwksProvider.remote[IO](ember, Uri.unsafeFromString(corp.hcursor.get[String]("jwks_uri").toOption.get))
        asKeys  <- JwksProvider.remote[IO](ember, Uri.unsafeFromString(as.jwks_uri.get))
        replay  <- ReplayCache.inMemory[IO]()
        audit   <- Ref.of[IO, List[AuthEvent]](Nil)
        clientSecret = setting("IRON_MCP_KC_CORP_SECRET")
        issuer = IdJagIssuer.Config[IO](
                   issuer = issuerUrl,
                   tokenEndpoint = s"$issuerUrl/token",
                   signer = IdJagIssuer.Signer(key("IRON_MCP_KC_IDJAG_KEY"), publicOf("IRON_MCP_KC_IDJAG_KEY"), JwsAlgorithm.ES256, "idjag-1"),
                   ssoIssuer = corp.hcursor.get[String]("issuer").toOption.get,
                   ssoKeys = corpKeys,
                   clients = ClientRegistry[IO](Map(setting("IRON_MCP_KC_CORP_CLIENT") -> ClientCredential.Secret.of(clientSecret).toOption.get), issuerUrl, s"$issuerUrl/token", replay),
                   policy = IdJagIssuer.Policy.rules[IO](
                     IdJagIssuer.Rule(
                       client = setting("IRON_MCP_KC_CORP_CLIENT"),
                       authorizationServer = as.issuer,
                       resource = resource,
                       who = IdJagIssuer.Who.Groups(Set("engineering")),
                       scopes = Set("mcp:read", "mcp:export"),
                       clientIdAtServer = Some(setting("IRON_MCP_KC_ENTERPRISE_CLIENT"))
                     )
                   ),
                   audit = e => audit.update(_ :+ e)
                 )
        idp      = IdJagIssuer.routes[IO](issuer).orNotFound
        host     = Uri.unsafeFromString(issuerUrl).host
        http     = Client[IO](req => if req.uri.host == host then Resource.eval(idp.run(req)) else ember.run(req))
        config   = EnterpriseAuth.Config(
                     idpIssuer = issuerUrl,
                     idpTokenEndpoint = s"$issuerUrl/token",
                     idpClient = ClientAuth.SecretBasic(setting("IRON_MCP_KC_CORP_CLIENT"), clientSecret),
                     authorizationServer = as,
                     asClient = ClientAuth.SecretBasic(setting("IRON_MCP_KC_ENTERPRISE_CLIENT"), setting("IRON_MCP_KC_ENTERPRISE_SECRET")),
                     resource = resource,
                     requireProfile = false // Keycloak 26.7 redeems ID-JAGs without advertising the profile
                   )
      yield World(http, as, config, TokenValidator.jwks[IO](asKeys, TokenValidator.Config(as.issuer, resource)), audit)
    }

  test("live: an engineer signs in once, and Keycloak redeems the ID-JAG for a token the MCP server accepts"):
    requireEnv()
    world.use { w =>
      for
        idToken <- signIn(w.http, "ada", setting("IRON_MCP_KC_CORP_ADA_PASSWORD"))
        token   <- EnterpriseAuth.authorizer[IO](w.http, w.config, IO.pure(EnterpriseAuth.Subject.IdToken(idToken)))(Set("mcp:read"))
        claims  <- w.validator.validate(token.access_token)
        events  <- w.audit.get
      yield
        val c = claims.fold(e => fail(s"${e.kind}: ${e.description}"), identity)
        assert(c.scopes.contains("mcp:read"), c.scope.toString)
        assert(!c.scopes.contains("mcp:export"), "only what was asked")
        assertEquals(c.clientId, Some(setting("IRON_MCP_KC_ENTERPRISE_CLIENT")))
        assertEquals(events.map(e => (e.allowed, e.subject.isDefined, e.granted)), List((true, true, Set("mcp:read"))))
    }

  test("live: someone outside the policy gets no ID-JAG, so never reaches Keycloak"):
    requireEnv()
    world.use { w =>
      for
        idToken <- signIn(w.http, "grace", setting("IRON_MCP_KC_CORP_GRACE_PASSWORD"))
        result  <- EnterpriseAuth.authorizer[IO](w.http, w.config, IO.pure(EnterpriseAuth.Subject.IdToken(idToken)))(Set("mcp:read")).attempt
      yield assert(result.left.exists { case OAuthError.TokenEndpoint(400, "invalid_target", _) => true; case _ => false }, result.toString)
    }

  test("live: Keycloak refuses a replayed ID-JAG, one signed by an untrusted key, and one for another client"):
    requireEnv()
    world.use { w =>
      for
        idToken <- signIn(w.http, "ada", setting("IRON_MCP_KC_CORP_ADA_PASSWORD"))
        jag     <- EnterpriseAuth.requestIdJag[IO](w.http, w.config, EnterpriseAuth.Subject.IdToken(idToken), Set("mcp:read"))
        first   <- EnterpriseAuth.redeem[IO](w.http, w.config, jag.access_token).attempt
        again   <- EnterpriseAuth.redeem[IO](w.http, w.config, jag.access_token).attempt
        now     <- IO.realTime.map(_.toSeconds)
        payload  = Jwt.parse(jag.access_token).toOption.get.payload
        rogue    = AuthFixtures.ec
        forged   = Jwt.sign(JwsAlgorithm.ES256, rogue.getPrivate, JsonObject("typ" -> EnterpriseAuth.IdJagTyp.asJson, "kid" -> "idjag-1".asJson),
                     payload.mapObject(_.add("jti", s"forged-$now".asJson)))
        badSig  <- EnterpriseAuth.redeem[IO](w.http, w.config, forged).attempt
        jag2    <- EnterpriseAuth.requestIdJag[IO](w.http, w.config, EnterpriseAuth.Subject.IdToken(idToken), Set("mcp:read"))
        other   <- EnterpriseAuth.redeem[IO](w.http, w.config.copy(asClient = ClientAuth.SecretBasic(setting("IRON_MCP_KC_ENTERPRISE_CLIENT"), "wrong-secret")), jag2.access_token).attempt
      yield
        assert(first.isRight, first.toString)
        assert(again.isLeft, "assertion reuse is off")
        assert(badSig.isLeft, "signed by a key Keycloak was not given")
        assert(other.isLeft)
    }

  test("live: client credentials with private_key_jwt, a DPoP-bound token, and aud narrowed to the resource"):
    requireEnv()
    EmberClientBuilder.default[IO].build.use { http =>
      for
        as     <- json(http, s"${setting("IRON_MCP_KC_REALM")}/.well-known/oauth-authorization-server").map(_.as[AuthorizationServerMetadata].toOption.get)
        dpop   <- Dpop.Key.generate[IO]
        auth    = ClientAuth.PrivateKeyJwt("iron-mcp-machine", key("IRON_MCP_KC_MACHINE_KEY"), JwsAlgorithm.ES256, Some("machine-1"))
        get    <- IO.fromEither(ClientCredentials.authorizer[IO](http, as, auth, setting("IRON_MCP_KC_RESOURCE"), Some(dpop)))
        token  <- get(Set("mcp:read"))
        keys   <- JwksProvider.remote[IO](http, Uri.unsafeFromString(as.jwks_uri.get))
        claims <- TokenValidator.jwks[IO](keys, TokenValidator.Config(as.issuer, setting("IRON_MCP_KC_RESOURCE"))).validate(token.access_token)
        secret <- IO.fromEither(ClientCredentials.authorizer[IO](http, as, ClientAuth.SecretPost("iron-mcp-machine", "not-its-method"), setting("IRON_MCP_KC_RESOURCE"))).flatMap(_(Set("mcp:read"))).attempt
      yield
        assertEquals(token.token_type, "DPoP")
        val c = claims.fold(e => fail(e.description), identity)
        assertEquals(c.boundKeyThumbprint, Some(dpop.thumbprint), "bound to this client's DPoP key")
        assertEquals(c.aud, List(setting("IRON_MCP_KC_RESOURCE")), "RFC 8707: the audience narrowed to exactly the MCP server")
        assert(c.scopes.contains("mcp:read"))
        assert(secret.isLeft, "a client registered for private_key_jwt cannot fall back to a secret")
    }
