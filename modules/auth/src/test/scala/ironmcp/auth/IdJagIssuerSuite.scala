package ironmcp
package auth

import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.{Authorization, `Content-Type`}
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** The IdP side of Enterprise-Managed Authorization: [[IdJagIssuer]] with the
  * real [[EnterpriseAuth]] client on one side and [[EnterpriseAuth.verifyIdJag]]
  * (what the MCP server's authorization server checks) on the other.
  */
class IdJagIssuerSuite extends CatsEffectSuite:
  import AuthFixtures.*

  private val ssoIssuer   = "https://sso.corp.example"
  private val idpIssuer   = "http://idp.localhost"
  private val asIssuer    = "https://as.mcp.example"
  private val endpoint    = s"$idpIssuer/token"
  private val secret      = "desktop-secret-of-at-least-32-characters"
  private val ssoKey      = AuthFixtures.ec
  private val clientKey   = AuthFixtures.rsa2
  private val signer      = IdJagIssuer.Signer(AuthFixtures.ed.getPrivate, AuthFixtures.ed.getPublic, JwsAlgorithm.EdDSA, "jag-1")

  /** An ID token from the SSO, as Keycloak writes one. */
  private def idToken(sub: String, aud: String = "desktop", payloadTyp: Option[String] = Some("ID"), headerTyp: String = "JWT", extra: List[(String, Json)] = Nil): String =
    Jwt.sign(
      JwsAlgorithm.ES256,
      ssoKey.getPrivate,
      JsonObject("kid" -> "sso".asJson, "typ" -> headerTyp.asJson),
      Json.fromFields(
        List("iss" -> ssoIssuer.asJson, "sub" -> sub.asJson, "aud" -> aud.asJson, "iat" -> now.asJson, "exp" -> (now + 300).asJson, "acr" -> "1".asJson)
          ++ payloadTyp.map("typ" -> _.asJson) ++ extra
      )
    )

  private val engineering = List("groups" -> List("engineering").asJson, "email" -> "ada@corp.example".asJson, "email_verified" -> true.asJson)

  private val rules = List(
    IdJagIssuer.Rule("desktop", asIssuer, audience, IdJagIssuer.Who.Groups(Set("engineering")), Set("mcp:read", "mcp:export"), clientIdAtServer = Some("desktop-at-as")),
    IdJagIssuer.Rule("desktop", asIssuer, "https://admin.mcp.example/mcp", IdJagIssuer.Who.Anyone, Set("mcp:admin"), acr = Set("mfa"))
  )

  private def issuer(audit: Ref[IO, List[AuthEvent]]): IO[IdJagIssuer.Config[IO]] =
    ReplayCache.inMemory[IO]().map { replay =>
      val clients = Map(
        "desktop" -> ClientCredential.Secret.of(secret).toOption.get,
        "kiosk"   -> ClientCredential.Keys(KeySet.of(vkey(clientKey, "kiosk-1")))
      )
      IdJagIssuer.Config[IO](
        issuer = idpIssuer,
        tokenEndpoint = endpoint,
        signer = signer,
        ssoIssuer = ssoIssuer,
        ssoKeys = JwksProvider.static[IO](KeySet.of(vkey(ssoKey, "sso"))),
        clients = ClientRegistry[IO](clients, idpIssuer, endpoint, replay),
        policy = IdJagIssuer.Policy.rules[IO]((rules :+ IdJagIssuer.Rule("kiosk", asIssuer, audience, IdJagIssuer.Who.Anyone, Set("mcp:read")))*),
        audit = e => audit.update(_ :+ e)
      )
    }

  private def world: IO[(Client[IO], Ref[IO, List[AuthEvent]], IdJagIssuer.Config[IO])] =
    for
      audit  <- Ref.of[IO, List[AuthEvent]](Nil)
      config <- issuer(audit)
    yield (Client.fromHttpApp(IdJagIssuer.routes[IO](config).orNotFound), audit, config)

  private val asMetadata = AuthorizationServerMetadata(issuer = asIssuer, token_endpoint = s"$asIssuer/token")

  private def clientConfig(idpClient: ClientAuth = ClientAuth.SecretBasic("desktop", secret), resource: String = audience) =
    EnterpriseAuth.Config(idpIssuer, endpoint, idpClient, asMetadata, ClientAuth.Public("desktop-at-as"), resource)

  private def exchange(client: Client[IO], form: List[(String, String)], headers: Headers = Headers.empty): IO[(Status, Json)] =
    val req = Request[IO](Method.POST, Uri.unsafeFromString(endpoint))
      .withEntity(UrlForm(form*))
      .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))
      .putHeaders(headers)
    client.run(req).use(r => r.as[Json].map(r.status -> _))

  private def basic = Headers(Authorization(BasicCredentials("desktop", secret)))

  private def tokenExchange(subject: String, aud: String = asIssuer, resource: String = audience, scope: String = "mcp:read") = List(
    "grant_type"           -> EnterpriseAuth.TokenExchange,
    "requested_token_type" -> EnterpriseAuth.IdJagType,
    "subject_token_type"   -> EnterpriseAuth.Subject.IdTokenType,
    "subject_token"        -> subject,
    "audience"             -> aud,
    "resource"             -> resource,
    "scope"                -> scope
  )

  private def error(r: (Status, Json)) = r._2.hcursor.get[String]("error").toOption

  test("an engineer gets an ID-JAG the MCP server's authorization server accepts, for the scopes asked"):
    for
      (http, audit, _) <- world
      jag    <- EnterpriseAuth.requestIdJag[IO](http, clientConfig(), EnterpriseAuth.Subject.IdToken(idToken("ada", extra = engineering)), Set("mcp:read"))
      replay <- ReplayCache.inMemory[IO]()
      claims <- EnterpriseAuth.verifyIdJag[IO](jag.access_token, asIssuer, Map(idpIssuer -> JwksProvider.static[IO](KeySet.of(vkey(AuthFixtures.ed, "jag-1")))), "desktop-at-as", Set(audience), replay)
      events <- audit.get
    yield
      assertEquals(jag.issued_token_type, Some(EnterpriseAuth.IdJagType))
      assertEquals(jag.token_type, "N_A")
      assertEquals(jag.grantedScopes, Set("mcp:read"))
      val c = claims.fold(e => fail(e.description), identity)
      assertEquals(c.sub, Some("ada"))
      assertEquals(c.clientId, Some("desktop-at-as"), "the client's id at the authorization server, from the rule")
      assertEquals(c.claim[String]("resource"), Some(audience))
      assertEquals(c.claim[String]("email"), Some("ada@corp.example"))
      assertEquals(c.claim[String]("acr"), Some("1"))
      assert(c.claim[List[String]]("groups").isEmpty, "only the listed claims are copied")
      assertEquals(events.map(e => (e.allowed, e.reason, e.subject, e.clientId, e.granted)), List((true, "ok", Some("ada"), Some("desktop"), Set("mcp:read"))))
      assert(!events.asJson.noSpaces.contains(jag.access_token.take(40)), "the audit never holds a token")

  test("scopes beyond the rule are narrowed away; nothing left is invalid_scope"):
    for
      (http, _, _) <- world
      wide  <- exchange(http, tokenExchange(idToken("ada", extra = engineering), scope = "mcp:read mcp:admin"), basic)
      none  <- exchange(http, tokenExchange(idToken("ada", extra = engineering), scope = "mcp:admin"), basic)
      all   <- exchange(http, tokenExchange(idToken("ada", extra = engineering), scope = ""), basic)
    yield
      assertEquals(wide._2.hcursor.get[String]("scope").toOption, Some("mcp:read"))
      assertEquals(error(none), Some("invalid_scope"))
      assertEquals(all._2.hcursor.get[String]("scope").toOption, Some("mcp:export mcp:read"), "no scope asked: everything the rule allows")

  test("deny by default: no group, other audience or other resource all read the same"):
    for
      (http, audit, _) <- world
      grace    <- exchange(http, tokenExchange(idToken("grace")), basic)
      otherAs  <- exchange(http, tokenExchange(idToken("ada", extra = engineering), aud = "https://evil.example"), basic)
      otherRes <- exchange(http, tokenExchange(idToken("ada", extra = engineering), resource = "https://other.example/mcp"), basic)
      events   <- audit.get
    yield
      List(grace, otherAs, otherRes).foreach { r =>
        assertEquals(r._1, Status.BadRequest)
        assertEquals(error(r), Some("invalid_target"))
      }
      assertEquals(Set(grace._2, otherAs._2, otherRes._2).size, 1, "the same answer, so the policy cannot be mapped")
      assert(events.forall(!_.allowed))
      assert(events.head.detail.exists(_.contains("grace")), "the audit says why")

  test("a rule that asks for MFA: insufficient_user_authentication until the sign-in has it"):
    for
      (http, _, _) <- world
      password <- exchange(http, tokenExchange(idToken("ada"), resource = "https://admin.mcp.example/mcp", scope = "mcp:admin"), basic)
      mfa      <- exchange(http, tokenExchange(idToken("ada", extra = List("acr" -> "mfa".asJson)), resource = "https://admin.mcp.example/mcp", scope = "mcp:admin"), basic)
    yield
      assertEquals(error(password), Some("insufficient_user_authentication"))
      assertEquals(mfa._1, Status.Ok)

  test("only ID tokens for this client: access tokens, refresh tokens, ID-JAGs and other clients' ID tokens are invalid_grant"):
    for
      (http, _, _) <- world
      access    <- exchange(http, tokenExchange(idToken("ada", payloadTyp = Some("Bearer"), extra = engineering)), basic)
      at        <- exchange(http, tokenExchange(idToken("ada", payloadTyp = None, headerTyp = "at+jwt", extra = engineering)), basic)
      refresh   <- exchange(http, tokenExchange(idToken("ada", payloadTyp = Some("Refresh"), extra = engineering)), basic)
      scoped    <- exchange(http, tokenExchange(idToken("ada", payloadTyp = None, extra = engineering :+ ("scope" -> "openid".asJson))), basic)
      other     <- exchange(http, tokenExchange(idToken("ada", aud = "someone-else", extra = engineering)), basic)
      forged    <- exchange(http, tokenExchange(sign(JwsAlgorithm.RS256, rsa.getPrivate, Json.obj("iss" -> ssoIssuer.asJson, "sub" -> "ada".asJson, "aud" -> "desktop".asJson, "exp" -> (now + 60).asJson), kid = Some("sso"), typ = Some("JWT"))), basic)
    yield List(access, at, refresh, scoped, other, forged).foreach(r => assertEquals(error(r), Some("invalid_grant"), r.toString))

  test("client authentication: wrong secret is 401 with a Basic challenge; two methods at once is invalid_request"):
    for
      (http, _, _) <- world
      wrong <- http.run(
                 Request[IO](Method.POST, Uri.unsafeFromString(endpoint))
                   .withEntity(UrlForm(tokenExchange(idToken("ada", extra = engineering))*))
                   .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))
                   .putHeaders(Authorization(BasicCredentials("desktop", "not-the-secret-not-the-secret-not")))
               ).use(r => IO.pure((r.status, r.headers.get(CIString("WWW-Authenticate")).map(_.head.value))))
      both  <- exchange(http, tokenExchange(idToken("ada", extra = engineering)) ++ List("client_id" -> "desktop", "client_secret" -> secret), basic)
      none  <- exchange(http, tokenExchange(idToken("ada", extra = engineering)))
    yield
      assertEquals(wrong._1, Status.Unauthorized)
      assert(wrong._2.exists(_.startsWith("Basic")), wrong.toString)
      assertEquals(error(both), Some("invalid_request"))
      assertEquals(error(none), Some("invalid_client"))

  test("private_key_jwt at the IdP: accepted once, a replayed assertion is refused"):
    for
      (http, _, _) <- world
      auth       = ClientAuth.PrivateKeyJwt("kiosk", clientKey.getPrivate, JwsAlgorithm.RS256, Some("kiosk-1"))
      (fields, _) <- auth.credentials[IO](idpIssuer, endpoint)
      body        = tokenExchange(idToken("ada", aud = "kiosk")) ++ fields
      first      <- exchange(http, body)
      again      <- exchange(http, body)
      asDesktop  <- exchange(http, tokenExchange(idToken("ada", aud = "kiosk")), basic)
    yield
      assertEquals(first._1, Status.Ok, first.toString)
      assertEquals(error(again), Some("invalid_client"))
      assertEquals(error(asDesktop), Some("invalid_grant"), "an ID token for the kiosk is not the desktop's")

  test("a secret client cannot switch to an assertion, and a short secret cannot be registered"):
    for
      (http, _, _) <- world
      auth        = ClientAuth.PrivateKeyJwt("desktop", clientKey.getPrivate, JwsAlgorithm.RS256, Some("kiosk-1"))
      (fields, _) <- auth.credentials[IO](idpIssuer, endpoint)
      r          <- exchange(http, tokenExchange(idToken("ada", extra = engineering)) ++ fields)
    yield
      assertEquals(error(r), Some("invalid_client"))
      assert(ClientCredential.Secret.of("hunter2").isLeft)

  test("repeated parameters, delegation and the wrong grant are refused"):
    for
      (http, _, _) <- world
      twice  <- exchange(http, tokenExchange(idToken("ada", extra = engineering)) :+ ("audience" -> "https://evil.example"), basic)
      actor  <- exchange(http, tokenExchange(idToken("ada", extra = engineering)) :+ ("actor_token" -> "x"), basic)
      code   <- exchange(http, ("grant_type" -> "authorization_code") :: tokenExchange(idToken("ada")).tail, basic)
    yield
      assertEquals(error(twice), Some("invalid_request"))
      assertEquals(error(actor), Some("invalid_request"))
      assertEquals(error(code), Some("unsupported_grant_type"))

  test("responses are no-store; JWKS and metadata are published"):
    for
      (http, _, _) <- world
      res  <- http.run(
                Request[IO](Method.POST, Uri.unsafeFromString(endpoint))
                  .withEntity(UrlForm(tokenExchange(idToken("ada", extra = engineering))*))
                  .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))
                  .putHeaders(basic)
              ).use(r => IO.pure(r.headers.get(CIString("Cache-Control")).map(_.head.value)))
      jwks <- http.expect[Json](s"$idpIssuer/jwks").map(_.as[JwksKeySet].toOption.get)
      meta <- http.expect[Json](s"$idpIssuer/.well-known/oauth-authorization-server")
    yield
      assertEquals(res, Some("no-store"))
      assertEquals(jwks.keys.flatMap(_.kid), List("jag-1"))
      assertEquals(meta.hcursor.get[List[String]]("grant_types_supported").toOption, Some(List(EnterpriseAuth.TokenExchange)))
      assertEquals(meta.hcursor.get[List[String]]("token_endpoint_auth_methods_supported").toOption, Some(List("client_secret_basic", "private_key_jwt")))

  test("the client refuses an ID-JAG addressed elsewhere (IdP mix-up), before it reaches any server"):
    val config = clientConfig()
    def jag(aud: String, resource: String, typ: String = EnterpriseAuth.IdJagTyp) =
      Jwt.sign(JwsAlgorithm.EdDSA, AuthFixtures.ed.getPrivate, JsonObject("typ" -> typ.asJson),
        Json.obj("iss" -> idpIssuer.asJson, "aud" -> aud.asJson, "resource" -> resource.asJson, "exp" -> (now + 60).asJson))
    assert(EnterpriseAuth.checkIdJag(config, jag(asIssuer, audience), now).isRight)
    assert(EnterpriseAuth.checkIdJag(config, jag("https://evil.example", audience), now).isLeft)
    assert(EnterpriseAuth.checkIdJag(config, jag(asIssuer, "https://other.example/mcp"), now).isLeft)
    assert(EnterpriseAuth.checkIdJag(config, jag(asIssuer, audience, typ = "JWT"), now).isLeft)
    assert(EnterpriseAuth.checkIdJag(config, jag(asIssuer, audience), now + 120).isLeft)

  test("Pem: keys round-trip; legacy files get the command that converts them"):
    val pub = Pem.encode(AuthFixtures.ec.getPublic)
    assertEquals(Pem.publicKey(pub).map(_.getEncoded.toSeq), Right(AuthFixtures.ec.getPublic.getEncoded.toSeq))
    val priv = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder.encodeToString(AuthFixtures.ed.getPrivate.getEncoded) + "\n-----END PRIVATE KEY-----\n"
    assertEquals(Pem.privateKey(priv).map(_.getAlgorithm), Right(AuthFixtures.ed.getPrivate.getAlgorithm))
    assert(Pem.privateKey("-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----").left.exists(_.contains("openssl pkcs8")))

  test("an ID token older than maxIdTokenAge is refused however long it lives"):
    for
      audit  <- Ref.of[IO, List[AuthEvent]](Nil)
      config <- issuer(audit).map(_.copy(maxIdTokenAge = 10.minutes))
      http    = Client.fromHttpApp(IdJagIssuer.routes[IO](config).orNotFound)
      old     = Jwt.sign(JwsAlgorithm.ES256, ssoKey.getPrivate, JsonObject("kid" -> "sso".asJson),
                  Json.fromFields(List("iss" -> ssoIssuer.asJson, "sub" -> "ada".asJson, "aud" -> "desktop".asJson, "typ" -> "ID".asJson, "iat" -> (now - 3600).asJson, "exp" -> (now + 3600).asJson) ++ engineering))
      r      <- exchange(http, tokenExchange(old), basic)
    yield assertEquals(error(r), Some("invalid_grant"))
