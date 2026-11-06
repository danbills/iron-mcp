package ironmcp
package auth

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets.UTF_8

class AudienceSuite extends CatsEffectSuite:

  val hmacSecret: Array[Byte] = "aud-test-secret-aud-test-secret-aud-test-secret".getBytes(UTF_8)
  val serverResource = "https://mcp.internal.megamote.com/mcp"

  def mintToken(
      aud: List[String],
      expOffsetSeconds: Long = 3600,
      iss: String = "https://idp.internal.megamote.com"
  ): String =
    val header = Json.obj("alg" -> "HS256".asJson, "typ" -> "JWT".asJson)
    val now = System.currentTimeMillis() / 1000
    val payload = Json.obj(
      "iss" -> iss.asJson,
      "sub" -> "agent-007".asJson,
      "aud" -> aud.asJson,
      "exp" -> (now + expOffsetSeconds).asJson,
      "scope" -> "tools:read tools:write".asJson
    )
    Jwt.signHs256(header, payload, hmacSecret)

  test("RFC 8707: accepts token with matching audience"):
    val token = mintToken(List(serverResource))
    val validator = TokenValidator.hs256[IO](
      hmacSecret,
      TokenValidator.Config(expectedResource = Some(serverResource))
    )

    validator.validate(token).map { res =>
      assert(res.isRight)
      assertEquals(res.toOption.get.aud, List(serverResource))
    }

  test("RFC 8707: accepts token with multiple audiences containing server resource"):
    val token = mintToken(List("https://other.service/api", serverResource))
    val validator = TokenValidator.hs256[IO](
      hmacSecret,
      TokenValidator.Config(expectedResource = Some(serverResource))
    )

    validator.validate(token).map { res =>
      assert(res.isRight)
    }

  test("RFC 8707: rejects genuine token minted for a different resource (The Downstream Trap)"):
    val token = mintToken(List("https://downstream-compromised.internal/api"))
    val validator = TokenValidator.hs256[IO](
      hmacSecret,
      TokenValidator.Config(expectedResource = Some(serverResource))
    )

    validator.validate(token).map {
      case Left(AuthError.WrongAudience(expected, actual)) =>
        assertEquals(expected, serverResource)
        assertEquals(actual, List("https://downstream-compromised.internal/api"))
      case other =>
        fail(s"Expected AuthError.WrongAudience, got $other")
    }

  test("rejects expired token"):
    val token = mintToken(List(serverResource), expOffsetSeconds = -120)
    val validator = TokenValidator.hs256[IO](
      hmacSecret,
      TokenValidator.Config(expectedResource = Some(serverResource), clockSkewSeconds = 5)
    )

    validator.validate(token).map {
      case Left(AuthError.Expired(_, _)) => ()
      case other                         => fail(s"Expected Expired error, got $other")
    }

  test("rejects token with unexpected issuer"):
    val token = mintToken(List(serverResource), iss = "https://rogue-idp.example")
    val validator = TokenValidator.hs256[IO](
      hmacSecret,
      TokenValidator.Config(
        expectedResource = Some(serverResource),
        expectedIssuer = Some("https://idp.internal.megamote.com")
      )
    )

    validator.validate(token).map {
      case Left(AuthError.IssuerMismatch(expected, actual)) =>
        assertEquals(expected, "https://idp.internal.megamote.com")
        assertEquals(actual, "https://rogue-idp.example")
      case other =>
        fail(s"Expected IssuerMismatch, got $other")
    }
