package ironmcp
package auth

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.Headers

class AuthCoverageSuite extends CatsEffectSuite:

  test("AuthError code, publicDescription, description, and kind cover all cases"):
    val all: List[AuthError] = List(
      AuthError.MissingToken,
      AuthError.InvalidRequest("bad"),
      AuthError.MalformedToken("bad"),
      AuthError.TokenTooLarge(100, 50),
      AuthError.UnsupportedAlgorithm("none"),
      AuthError.UnknownKey(Some("k1")),
      AuthError.InvalidSignature("bad"),
      AuthError.WrongTokenType("id"),
      AuthError.MissingClaim("sub"),
      AuthError.Expired(1, 2),
      AuthError.Premature(2, 1),
      AuthError.IssuedInFuture(2, 1),
      AuthError.TooOld(1, 100),
      AuthError.IssuerMismatch("a", "b"),
      AuthError.WrongAudience("a", List("b")),
      AuthError.Revoked("jti"),
      AuthError.Inactive,
      AuthError.KeysUnavailable("down"),
      AuthError.InsufficientScope(Set("a"), Set("b")),
      AuthError.InvalidDpopProof("bad"),
      AuthError.DpopRequired
    )
    all.foreach { e =>
      assert(e.publicDescription.nonEmpty)
      assert(e.description.nonEmpty)
      assert(e.kind.nonEmpty)
      val _ = e.code
    }

  test("IdJagIssuer.Who covers Anyone, Subjects, Groups, EmailDomains, Matching and toString"):
    val claims = TokenClaims(
      iss = "https://idp.example",
      sub = Some("alice"),
      aud = List("mcp"),
      exp = 9999999999L,
      rawJson = Json.obj(
        "groups"         -> List("eng", "ops").asJson,
        "email"          -> "alice@Example.COM".asJson,
        "email_verified" -> true.asJson
      )
    )
    val whoList = List(
      IdJagIssuer.Who.Anyone,
      IdJagIssuer.Who.Subjects(Set("alice")),
      IdJagIssuer.Who.Groups(Set("eng")),
      IdJagIssuer.Who.EmailDomains(Set("example.com")),
      IdJagIssuer.Who.Matching("has-sub", _.sub.contains("alice"))
    )
    whoList.foreach { w =>
      assert(w.covers(claims))
      assert(w.toString.nonEmpty)
    }
    assert(!IdJagIssuer.Who.Subjects(Set("bob")).covers(claims))
    assert(!IdJagIssuer.Who.Groups(Set("sales")).covers(claims))
    assert(!IdJagIssuer.Who.EmailDomains(Set("other.com")).covers(claims))

  test("ClientRegistry rejects wrong secret, wrong method, unknown client, and bad assertions"):
    for
      replay <- ReplayCache.inMemory[IO]()
      reg = ClientRegistry[IO](
        Map(
          "sec-client" -> ClientCredential.Secret.of("correct-secret-of-at-least-32-chars").fold(e => fail(e.toString), identity)
        ),
        "https://idp.example",
        "https://idp.example/token",
        replay
      )
      badSec   <- reg.authenticate(Map("client_id" -> "sec-client", "client_secret" -> "wrong"), Headers.empty)
      noClient <- reg.authenticate(Map("client_id" -> "missing", "client_secret" -> "x"), Headers.empty)
      noAuth   <- reg.authenticate(Map.empty, Headers.empty)
      badType  <- reg.authenticate(Map("client_assertion" -> "a.b.c", "client_assertion_type" -> "wrong"), Headers.empty)
      badJwt   <- reg.authenticate(Map("client_assertion" -> "not-jwt", "client_assertion_type" -> ClientRegistry.AssertionType), Headers.empty)
    yield
      assert(badSec.isLeft)
      assert(noClient.isLeft)
      assert(noAuth.isLeft)
      assert(badType.isLeft)
      assert(badJwt.isLeft)

  test("AuthAudit.jsonLines writes each event as one JSON line; both feeds two; noop drops it"):
    val ev = AuthEvent(0L, true, "ok", None, "tools/call", "tool:x", Some("alice"), None, None, None, None, Set.empty, Set.empty)
    for
      lines <- IO.ref(Vector.empty[String])
      _     <- AuthAudit.both(AuthAudit.noop[IO], AuthAudit.jsonLines[IO](l => lines.update(_ :+ l))).record(ev)
      out   <- lines.get
    yield
      assertEquals(out.size, 1)
      assertEquals(io.circe.parser.parse(out.head).flatMap(_.hcursor.get[String]("subject")), Right("alice"))