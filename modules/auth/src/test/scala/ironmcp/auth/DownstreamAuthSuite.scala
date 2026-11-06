package ironmcp
package auth

import cats.effect.{IO, Ref}
import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.{CallToolParams, CallToolResult, ClientCapabilities, ElicitationCapability, InputRequiredResult, LatestProtocolVersion, RequestMeta}
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Location

import java.nio.charset.StandardCharsets.UTF_8

class DownstreamAuthSuite extends CatsEffectSuite:
  import AuthFixtures.*

  private val vaultKey  = Array.fill[Byte](32)(7)
  private val ticketKey = "downstream-ticket-key-of-at-least-32-bytes".getBytes(UTF_8)

  private val api = Downstream(
    name = "github",
    server = AuthorizationServerMetadata(
      issuer = "http://gh.localhost",
      authorization_endpoint = Some("http://gh.localhost/authorize"),
      token_endpoint = "http://gh.localhost/token",
      code_challenge_methods_supported = Some(List("S256")),
      token_endpoint_auth_methods_supported = Some(List("client_secret_basic"))
    ),
    client = ClientAuth.SecretBasic("mcp-server", "gh-secret"),
    callbackUrl = "http://mcp.localhost/oauth/downstream/github/callback",
    scopes = Set("repo:read")
  )

  /** The third party's token endpoint: a code for a token, checking PKCE. */
  private def thirdParty(challenges: Ref[IO, Map[String, String]]) = Client.fromHttpApp(HttpRoutes.of[IO] {
    case req @ POST -> Root / "token" =>
      req.as[UrlForm].flatMap { f =>
        val code = f.getFirst("code").getOrElse("")
        challenges.get.flatMap { c =>
          if c.get(code).contains(Pkce.challengeFor(f.getFirst("code_verifier").getOrElse(""))) then
            Ok(TokenResponse(s"gh-token-for-$code", "Bearer", Some(3600), Some("gh-refresh"), Some("repo:read")).asJson)
          else BadRequest(Json.obj("error" -> "invalid_grant".asJson))
        }
      }
  }.orNotFound)

  private val alice = TokenClaims(issuer, Some("alice"), List(audience), exp = Long.MaxValue)
  private val bob   = TokenClaims(issuer, Some("bob"), List(audience), exp = Long.MaxValue)

  final case class NoArgs(dummy: Option[String]) derives Decoder, JsonSchemaOf
  given RequestStateCodec = RequestStateCodec.hmac("request-state-key-of-at-least-32-bytes!".getBytes(UTF_8))

  private def tool(auth: DownstreamAuth[IO], caller: TokenClaims) =
    McpTool.interactive[NoArgs]("list_repos", "Lists your repositories.") { (_, ctx) =>
      auth.withToken(caller, ctx)(token => IO.pure(Interaction.done(s"listed with $token")))
    }

  private val meta = RequestMeta(LatestProtocolVersion, ClientCapabilities(elicitation = Some(ElicitationCapability(url = Some(JsonObject.empty)))))

  /** The URL the tool asked the user to open, from its first answer. */
  private def askedUrl(result: CallToolResult | InputRequiredResult): (String, Option[String]) = result match
    case r: InputRequiredResult =>
      val req = r.inputRequests.flatMap(_.get(DownstreamAuth.SignInKey)).get.hcursor.downField("params")
      (req.get[String]("url").toOption.get, r.requestState)
    case other => fail(s"expected an input request, got $other")

  /** A browser: opens the start link, "approves" at the third party, follows the redirect back. */
  private def browse(auth: DownstreamAuth[IO], url: String, challenges: Ref[IO, Map[String, String]], iss: Option[String] = Some("http://gh.localhost")): IO[(Status, Option[Status])] =
    for
      started <- auth.routes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(url)))
      back <- started.headers.get[Location] match
        case None => IO.pure(None)
        case Some(loc) =>
          val q    = loc.uri.query.params
          val code = s"code-${q("state").take(6)}"
          challenges.update(_.updated(code, q("code_challenge"))) *>
            auth.routes.orNotFound
              .run(Request[IO](Method.GET, Uri.unsafeFromString(api.callbackUrl).withQueryParams(Map("code" -> code, "state" -> q("state")) ++ iss.map("iss" -> _))))
              .map(r => Some(r.status))
    yield (started.status, back)

  private def setup(browserUser: Option[Request[IO] => IO[Option[Owner]]] = None) =
    for
      challenges <- Ref.of[IO, Map[String, String]](Map.empty)
      vault      <- TokenVault.inMemory[IO](vaultKey)
      auth       <- DownstreamAuth[IO](thirdParty(challenges), api, vault, ticketKey, "http://mcp.localhost/oauth/downstream/github/start", browserUser)
    yield (auth, vault, challenges)

  test("no token yet: the tool asks for a URL sign-in; after it, the retry runs with the user's own token"):
    for
      (auth, _, challenges) <- setup()
      t       = tool(auth, alice)
      first  <- t.run(CallToolParams("list_repos", meta), Emit.none[IO])
      (url, state) = askedUrl(first)
      (s1, s2) <- browse(auth, url, challenges)
      retry  <- t.run(CallToolParams("list_repos", meta, requestState = state, inputResponses = Some(Map(DownstreamAuth.SignInKey -> Json.obj("action" -> "accept".asJson)))), Emit.none[IO])
    yield
      assertEquals(s1, Status.Found)
      assertEquals(s2, Some(Status.Ok))
      retry match
        case r: CallToolResult => assert(r.content.toString.contains("listed with gh-token-for-code-"), r.toString)
        case other             => fail(other.toString)

  test("a sign-in link works once, and not after it is tampered with"):
    for
      (auth, _, challenges) <- setup()
      first  <- tool(auth, alice).run(CallToolParams("list_repos", meta), Emit.none[IO])
      (url, _) = askedUrl(first)
      tampered = url.replace("ticket=", "ticket=x")
      (bad, _) <- browse(auth, tampered, challenges)
      (ok, _)  <- browse(auth, url, challenges)
      (again, _) <- browse(auth, url, challenges)
    yield
      assertEquals(bad, Status.BadRequest)
      assertEquals(ok, Status.Found)
      assertEquals(again, Status.BadRequest)

  test("with browserUser, a forwarded link opened by someone else is refused"):
    for
      (auth, _, challenges) <- setup(browserUser = Some(_ => IO.pure(Some(Owner(issuer, "mallory")))))
      first <- tool(auth, alice).run(CallToolParams("list_repos", meta), Emit.none[IO])
      (status, _) <- browse(auth, askedUrl(first)._1, challenges)
    yield assertEquals(status, Status.Forbidden)

  test("a callback from another issuer, or replayed, stores nothing"):
    for
      (auth, vault, challenges) <- setup()
      first <- tool(auth, alice).run(CallToolParams("list_repos", meta), Emit.none[IO])
      (_, back) <- browse(auth, askedUrl(first)._1, challenges, iss = Some("http://evil.localhost"))
      held  <- vault.get(Owner(issuer, "alice"), "github")
    yield
      assertEquals(back, Some(Status.BadRequest))
      assertEquals(held, None)

  test("tokens are per user: bob never gets alice's"):
    for
      (auth, vault, challenges) <- setup()
      first <- tool(auth, alice).run(CallToolParams("list_repos", meta), Emit.none[IO])
      _     <- browse(auth, askedUrl(first)._1, challenges)
      forBob <- tool(auth, bob).run(CallToolParams("list_repos", meta), Emit.none[IO])
      a     <- vault.get(Owner(issuer, "alice"), "github")
    yield
      assert(a.isDefined)
      assert(forBob.isInstanceOf[InputRequiredResult], forBob.toString)

  test("the vault keeps only ciphertext, bound to its row"):
    for
      store <- SecretStore.inMemory[IO]
      vault <- IO.fromEither(TokenVault.encrypted[IO](store, vaultKey).left.map(new IllegalArgumentException(_)))
      token  = StoredToken("secret-access-token", Some("secret-refresh"), None, Set("x"))
      _     <- vault.put(Owner("i", "alice"), "gh", token)
      raw   <- store.get(s"i alice\u0000gh")
      _     <- store.put(s"i bob\u0000gh", raw.get) // an attacker copies alice's row to bob's
      moved <- vault.get(Owner("i", "bob"), "gh").attempt
      back  <- vault.get(Owner("i", "alice"), "gh")
    yield
      assert(!new String(raw.get, UTF_8).contains("secret-access-token"))
      assert(moved.isLeft)
      assertEquals(back, Some(token))
      assert(!token.toString.contains("secret"))

  test("whoami tells the caller what is unlocked and what each locked tool needs"):
    val policy = SessionTools.declare(AuthPolicy().tool("read", "mcp:read").tool("export", "mcp:read", "mcp:export"))
    val reader = TokenClaims(issuer, Some("alice"), List(audience), exp = 99, scope = Some("mcp:read"), rawJson = Json.obj("preferred_username" -> "alice".asJson))
    for
      signedIn  <- SessionTools.whoami[IO](Some(reader), policy).run(CallToolParams("whoami", meta), Emit.none[IO])
      anonymous <- SessionTools.whoami[IO](None, policy).run(CallToolParams("whoami", meta), Emit.none[IO])
    yield
      def session(r: CallToolResult | InputRequiredResult) = r match
        case c: CallToolResult => c.structuredContent.flatMap(_.asObject).get
        case other             => fail(other.toString)
      val s = session(signedIn)
      assertEquals(s("unlocked").flatMap(_.asArray).map(_.flatMap(_.asString)), Some(Vector("read", "sign_out", "whoami")))
      assertEquals(s("locked").flatMap(_.asArray).map(_.flatMap(_.hcursor.get[String]("name").toOption)), Some(Vector("export")))
      assertEquals(s("username").flatMap(_.asString), Some("alice"))
      assertEquals(session(anonymous)("signedIn").flatMap(_.asBoolean), Some(false))
      assertEquals(Access.of(SessionTools.whoami[IO](None, policy).definition), Some(Access.Public))

  test("sign_out revokes the session it was called with"):
    val claims = TokenClaims(issuer, Some("alice"), List(audience), exp = now + 3600, rawJson = Json.obj("sid" -> "s-9".asJson))
    for
      list <- RevocationList.inMemory[IO]
      _    <- SessionTools.signOut[IO](claims, list).run(CallToolParams("sign_out", meta), Emit.none[IO])
      r    <- list.revoked(claims)
    yield assertEquals(r.map(_.kind), Some("revoked"))

  test("whoami points each locked tool at an unlock tool that covers it, and does not list unlockers as locked"):
    val reader = TokenClaims(issuer, Some("alice"), List(audience), exp = 99, scope = Some("mcp:read"))
    val base   = AuthPolicy().tool("read", "mcp:read").tool("export", "mcp:read", "mcp:export").tool("admin", "mcp:admin")
    val unlock = SessionTools.unlock[IO]("unlock_export", Some(reader), base)("mcp:read", "mcp:export")
    val policy = base.withTools(unlock)
    val s      = SessionTools.session(Some(reader), policy)
    assertEquals(s.locked.map(l => l.name -> l.unlockWith), List("admin" -> None, "export" -> Some("unlock_export")))
    assertEquals(Access.of(unlock.definition), Some(Access.Scopes(Set("mcp:read", "mcp:export"))))
