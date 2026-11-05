package ironmcp
package auth

import cats.effect.{IO, Ref}
import cats.effect.std.SecureRandom
import io.circe.syntax.*
import ironmcp.client.McpClientError
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

/** The client OAuth flow against an in-memory resource server and
  * authorization server: no sockets.
  */
class OAuthClientSuite extends CatsEffectSuite:

  private val resource = "http://mcp.local/mcp"
  private val issuer   = "http://as.local/realms/mcp"
  private val prmUrl   = "http://mcp.local/.well-known/oauth-protected-resource/mcp"
  private val redirect = "http://127.0.0.1:7777/callback"

  private def asMetadata(issuerName: String, pkce: Option[List[String]] = Some(List("S256"))) =
    AuthorizationServerMetadata(
      issuer = issuerName,
      authorization_endpoint = Some(s"$issuer/protocol/openid-connect/auth"),
      token_endpoint = s"$issuer/protocol/openid-connect/token",
      code_challenge_methods_supported = pkce
    )

  /** What the fake authorization server saw. */
  final case class Seen(challenges: Map[String, String] = Map.empty, exchanges: Int = 0, refreshes: Int = 0, resources: List[String] = Nil)

  private def world(
      advertisedResource: String = resource,
      servers: List[String] = List(issuer),
      metadata: AuthorizationServerMetadata = asMetadata(issuer)
  ): IO[(Client[IO], Ref[IO, Seen])] =
    Ref.of[IO, Seen](Seen()).map { seen =>
      val app = HttpRoutes.of[IO] {
        case GET -> Root / ".well-known" / "oauth-protected-resource" / "mcp" =>
          Ok(ProtectedResourceMetadata(advertisedResource, servers).asJson)
        case GET -> Root / ".well-known" / "oauth-authorization-server" / "realms" / "mcp" =>
          Ok(metadata.asJson)
        case req @ POST -> Root / "realms" / "mcp" / "protocol" / "openid-connect" / "token" =>
          req.as[UrlForm].flatMap { form =>
            val get = (k: String) => form.getFirst(k).getOrElse("")
            seen.update(s => s.copy(resources = s.resources :+ get("resource"))) >>
              (get("grant_type") match
                case "authorization_code" =>
                  seen.get.flatMap { s =>
                    // The server keeps the challenge from the authorization request
                    // and checks the verifier against it (RFC 7636 §4.6).
                    if s.challenges.get(get("code")).contains(Pkce.challengeFor(get("code_verifier"))) then
                      seen.update(s => s.copy(exchanges = s.exchanges + 1)) >>
                        Ok(TokenResponse("access-1", "Bearer", Some(300), Some("refresh-1"), Some(get("code").stripPrefix("code:"))).asJson)
                    else BadRequest("""{"error":"invalid_grant"}""")
                  }
                case "refresh_token" if get("refresh_token") == "refresh-1" =>
                  seen.update(s => s.copy(refreshes = s.refreshes + 1)) >>
                    Ok(TokenResponse("access-refreshed", "Bearer", Some(300), None, form.getFirst("scope")).asJson)
                case _ => BadRequest("""{"error":"unsupported_grant_type"}""")
              )
          }
      }
      (Client.fromHttpApp(app.orNotFound), seen)
    }

  private def oauth(http: Client[IO], trusted: Set[String] = Set(issuer)): IO[OAuthClient[IO]] =
    SecureRandom.javaSecuritySecureRandom[IO].map(random => OAuthClient[IO](http, random, trusted))

  private val unauthorized = McpClientError.Http(
    401,
    List(s"""Bearer error="invalid_token", resource_metadata="$prmUrl""""),
    ""
  )

  /** A user agent that "approves" at once: it records the challenge the
    * authorization server would keep, and redirects back with a code that
    * carries the requested scopes.
    */
  private def approve(seen: Ref[IO, Seen], tamperState: Boolean = false): Uri => IO[AuthorizationResponse] = uri =>
    val q = uri.query.params
    assertEquals(q.get("code_challenge_method"), Some("S256"))
    assertEquals(q.get("resource"), Some(resource))
    assertEquals(q.get("redirect_uri"), Some(redirect))
    val code = s"code:${q.getOrElse("scope", "")}"
    seen.update(s => s.copy(challenges = s.challenges.updated(code, q("code_challenge")))).as(
      AuthorizationResponse(code, if tamperState then "forged" else q("state"))
    )

  test("PKCE S256 matches the RFC 7636 appendix B vector"):
    assertEquals(Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")

  test("a generated verifier is 43 URL-safe characters and its challenge follows from it"):
    SecureRandom.javaSecuritySecureRandom[IO].flatMap(Pkce.generate(_)).map { pair =>
      assertEquals(pair.verifier.length, 43)
      assert(pair.verifier.forall(c => c.isLetterOrDigit || c == '-' || c == '_'))
      assertEquals(pair.challenge, Pkce.challengeFor(pair.verifier))
    }

  test("discovery follows resource_metadata from a 401 to the authorization server's RFC 8414 metadata"):
    for
      (http, _) <- world()
      client    <- oauth(http)
      found     <- client.discover(unauthorized, resource)
    yield
      assertEquals(found.resource.resource, resource)
      assertEquals(found.authorizationServer.issuer, issuer)

  test("metadata describing a different resource is refused"):
    for
      (http, _) <- world(advertisedResource = "http://evil.local/mcp")
      client    <- oauth(http)
      result    <- client.discover(unauthorized, resource).attempt
    yield assert(result.left.exists(_.isInstanceOf[OAuthError.ResourceMismatch]), result.toString)

  test("an authorization server the client does not trust is never contacted"):
    for
      (http, _) <- world(servers = List("http://attacker.local"))
      client    <- oauth(http)
      result    <- client.discover(unauthorized, resource).attempt
    yield assert(result.left.exists(_.isInstanceOf[OAuthError.UntrustedAuthorizationServer]), result.toString)

  test("metadata that names another issuer is refused"):
    for
      (http, _) <- world(metadata = asMetadata("http://as.local/realms/other"))
      client    <- oauth(http)
      result    <- client.discover(unauthorized, resource).attempt
    yield assert(result.left.exists(_.isInstanceOf[OAuthError.IssuerMismatch]), result.toString)

  test("an authorization server without PKCE S256 is refused before the user is sent there"):
    for
      (http, _) <- world(metadata = asMetadata(issuer, pkce = None))
      client    <- oauth(http)
      found     <- client.discover(unauthorized, resource)
      result    <- client.authorizationRequest(found, "mcp-client", redirect, Set("reports:read")).attempt
    yield assert(result.left.exists(_.isInstanceOf[OAuthError.PkceUnsupported]), result.toString)

  test("the authorizer runs the code flow, refreshes within the grant, and re-authorizes to widen it"):
    for
      (http, seen) <- world()
      client       <- oauth(http)
      found        <- client.discover(unauthorized, resource)
      authorize    <- client.authorizer(found, "mcp-client", redirect, approve(seen))
      first        <- authorize(Set("reports:read"))
      again        <- authorize(Set("reports:read"))
      wider        <- authorize(Set("reports:read", "reports:export"))
      after        <- seen.get
    yield
      assertEquals(first.access_token, "access-1")
      assertEquals(first.grantedScopes, Set("reports:read"))
      assertEquals(again.access_token, "access-refreshed")
      assertEquals(wider.grantedScopes, Set("reports:read", "reports:export"))
      assertEquals((after.exchanges, after.refreshes), (2, 1))
      assert(after.resources.forall(_ == resource), "every token request carries the RFC 8707 resource")

  test("plugged into StepUpClient, a 401 retry refreshes instead of asking the user again"):
    for
      (http, seen) <- world()
      client       <- oauth(http)
      found        <- client.discover(unauthorized, resource)
      authorize    <- client.authorizer(found, "mcp-client", redirect, approve(seen))
      stepUp       <- StepUpClient.create[IO](authorize)
      _            <- stepUp.initialAuthorize(Set("reports:read"))
      attempts     <- Ref.of[IO, Int](0)
      // The first call is rejected with a 401 (an expired token); the retry succeeds.
      result <- stepUp.executeWithRetry(
                  attempts.getAndUpdate(_ + 1).flatMap(n => if n == 0 then IO.raiseError(unauthorized) else IO.pure("ok")),
                  "read_summary",
                  retriesLeft = 2
                )
      after <- seen.get
    yield
      assertEquals(result, "ok")
      assertEquals((after.exchanges, after.refreshes), (1, 1))

  test("a redirect whose state does not match the request is refused"):
    for
      (http, seen) <- world()
      client       <- oauth(http)
      found        <- client.discover(unauthorized, resource)
      authorize    <- client.authorizer(found, "mcp-client", redirect, approve(seen, tamperState = true))
      result       <- authorize(Set("reports:read")).attempt
    yield assertEquals(result, Left(OAuthError.StateMismatch))
