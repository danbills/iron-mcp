package ironmcp
package auth

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** Each check of the validator, and each way around it that must fail. */
class TokenValidatorSuite extends CatsEffectSuite:
  import AuthFixtures.*

  private val rsaValidator = jwksValidator(vkey(rsa, "k1"))

  private def refused(v: TokenValidator[IO], token: String, kind: String) =
    v.validate(token).map(r => assertEquals(r.left.map(_.kind), Left(kind), r.toString))

  test("a good RS256 access token passes, with its claims"):
    rsaValidator.validate(rs(claims(scope = "mcp:read mcp:export"))).map { r =>
      val c = r.toOption.get
      assertEquals(c.sub, Some("alice"))
      assertEquals(c.scopes, Set("mcp:read", "mcp:export"))
    }

  test("ES256 and EdDSA tokens pass with their keys"):
    for
      e <- jwksValidator(vkey(ec, "k1")).validate(sign(JwsAlgorithm.ES256, ec.getPrivate, claims()))
      d <- jwksValidator(vkey(ed, "k1")).validate(sign(JwsAlgorithm.EdDSA, ed.getPrivate, claims()))
    yield
      assert(e.isRight, e.toString)
      assert(d.isRight, d.toString)

  test("RFC 8707: a genuine token for another resource is refused (the downstream trap)"):
    refused(rsaValidator, rs(claims(aud = Json.fromString("https://other.example/mcp"))), "audience")

  test("aud as an array containing this resource passes"):
    rsaValidator.validate(rs(claims(aud = List("https://other.example", audience).asJson))).map(r => assert(r.isRight))

  test("a token from another issuer is refused"):
    refused(rsaValidator, rs(claims(iss = "https://rogue.example")), "issuer")

  test("a token without exp is refused: it would never expire"):
    refused(rsaValidator, rs(claims(exp = None)), "missing_claim")

  test("an expired token is refused, beyond the clock skew"):
    refused(rsaValidator, rs(claims(exp = Some(now - 120))), "expired")

  test("nbf in the future and iat in the future are refused"):
    for
      _ <- refused(rsaValidator, rs(claims(extra = List("nbf" -> (now + 600).asJson))), "premature")
      _ <- refused(rsaValidator, rs(claims().deepMerge(Json.obj("iat" -> (now + 600).asJson))), "future_iat")
    yield ()

  test("maxAge refuses old tokens"):
    val strict = TokenValidator.jwks[IO](JwksProvider.static[IO](KeySet.of(vkey(rsa, "k1"))), config.copy(maxAge = Some(5.minutes)))
    refused(strict, rs(claims().deepMerge(Json.obj("iat" -> (now - 3600).asJson))), "too_old")

  test("an OIDC ID token from the same issuer is not an access token"):
    for
      _ <- refused(rsaValidator, rs(claims(), typ = Some("JWT")), "token_type")
      _ <- refused(rsaValidator, rs(claims(extra = List("typ" -> "ID".asJson)), typ = Some("JWT")), "token_type")
      _ <- refused(rsaValidator, rs(claims(extra = List("typ" -> "Refresh".asJson)), typ = Some("JWT")), "token_type")
    yield ()

  test("a Keycloak access token (typ JWT, payload typ Bearer) passes; application/at+jwt passes"):
    for
      k <- rsaValidator.validate(rs(claims(extra = List("typ" -> "Bearer".asJson)), typ = Some("JWT")))
      a <- rsaValidator.validate(rs(claims(), typ = Some("application/at+jwt")))
    yield
      assert(k.isRight, k.toString)
      assert(a.isRight, a.toString)

  test("a DPoP-bound Keycloak token (payload typ DPoP) passes with its cnf.jkt, and is refused without one"):
    val bound = List("typ" -> "DPoP".asJson, "cnf" -> Json.obj("jkt" -> "0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I".asJson))
    for
      ok <- rsaValidator.validate(rs(claims(extra = bound), typ = Some("JWT")))
      _  <- refused(rsaValidator, rs(claims(extra = List("typ" -> "DPoP".asJson)), typ = Some("JWT")), "token_type")
    yield assert(ok.isRight, ok.toString)

  test("alg confusion: HS256 signed with the RSA public key is refused by a public-key validator"):
    val publicBytes = rsa.getPublic.getEncoded
    val forged = Jwt.sign(JwsAlgorithm.HS256, Jwt.hmacKey(publicBytes), io.circe.JsonObject("kid" -> "k1".asJson, "typ" -> "at+jwt".asJson), claims())
    refused(rsaValidator, forged, "algorithm")

  test("alg none is refused"):
    val header  = Jwt.base64UrlEncode("""{"alg":"none","typ":"at+jwt"}""".getBytes)
    val payload = Jwt.base64UrlEncode(claims().noSpaces.getBytes)
    rsaValidator.validate(s"$header.$payload.AA").map(r => assert(r.isLeft))

  test("a token signed by another key under the same kid is refused"):
    refused(rsaValidator, sign(JwsAlgorithm.RS256, rsa2.getPrivate, claims()), "signature")

  test("an ES256 token cannot be checked with an RSA key that shares its kid"):
    refused(rsaValidator, sign(JwsAlgorithm.ES256, ec.getPrivate, claims()), "signature")

  test("an unknown kid is refused"):
    refused(rsaValidator, rs(claims(), kid = Some("nope")), "unknown_key")

  test("no kid with two keys is refused rather than guessed"):
    refused(jwksValidator(vkey(rsa, "a"), vkey(rsa2, "b")), rs(claims(), kid = None), "malformed")

  test("a token over the size limit is refused before parsing"):
    refused(rsaValidator, rs(claims(extra = List("pad" -> ("x" * 9000).asJson))), "too_large")

  test("a scp array counts as scopes"):
    rsaValidator.validate(rs(claims(extra = List("scp" -> List("a", "b").asJson)))).map(r => assertEquals(r.map(_.scopes), Right(Set("a", "b"))))

  test("revocation: a revoked session, token or subject is refused"):
    for
      list <- RevocationList.inMemory[IO]
      v     = rsaValidator.withRevocation(list)
      bySid = rs(claims(extra = List("sid" -> "s1".asJson)))
      byJti = rs(claims(extra = List("jti" -> "j1".asJson)))
      _    <- v.validate(bySid).map(r => assert(r.isRight))
      _    <- list.revokeSession("s1", now + 3600)
      _    <- list.revokeToken("j1", now + 3600)
      _    <- refused(v, bySid, "revoked")
      _    <- refused(v, byJti, "revoked")
      _    <- list.revokeSubject(issuer, "bob", now + 1, now + 3600)
      _    <- refused(v, rs(claims(sub = "bob")), "revoked")
      _    <- v.validate(rs(claims(sub = "carol"))).map(r => assert(r.isRight))
    yield ()

  test("layered: the remote check runs only when asked, and its refusal wins"):
    val no: TokenValidator[IO] = _ => IO.pure(Left(AuthError.Inactive))
    val v = TokenValidator.layered(rsaValidator, no)(_.scopes.contains("mcp:admin"))
    for
      _ <- v.validate(rs(claims(scope = "mcp:read"))).map(r => assert(r.isRight))
      _ <- refused(v, rs(claims(scope = "mcp:admin")), "inactive")
    yield ()
