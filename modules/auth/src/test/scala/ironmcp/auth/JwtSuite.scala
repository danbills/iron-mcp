package ironmcp
package auth

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator

class JwtSuite extends FunSuite:

  val hmacSecret: Array[Byte] = "demo-super-secret-key-for-hmac-sha256-tests".getBytes(UTF_8)

  test("HS256 roundtrip minting and verification"):
    val header = Json.obj("alg" -> "HS256".asJson, "typ" -> "JWT".asJson)
    val payload = Json.obj(
      "iss" -> "https://issuer.example".asJson,
      "sub" -> "user-123".asJson,
      "aud" -> "https://server.example/mcp".asJson,
      "scope" -> "reports:read reports:export".asJson,
      "exp" -> (System.currentTimeMillis() / 1000 + 3600).asJson
    )

    val token = Jwt.signHs256(header, payload, hmacSecret)
    val parts = Jwt.parse(token).toOption.get
    assertEquals(parts.algorithm, "HS256")

    val claims = Jwt.verifyHs256(parts, hmacSecret).toOption.get
    assertEquals(claims.iss, "https://issuer.example")
    assertEquals(claims.sub, Some("user-123"))
    assertEquals(claims.aud, List("https://server.example/mcp"))
    assertEquals(claims.scopes, Set("reports:read", "reports:export"))
    assert(claims.hasScopes(Set("reports:read")))
    assert(!claims.hasScopes(Set("reports:admin")))

  test("HS256 rejects corrupted signature"):
    val header = Json.obj("alg" -> "HS256".asJson, "typ" -> "JWT".asJson)
    val payload = Json.obj("iss" -> "https://test".asJson)
    val token = Jwt.signHs256(header, payload, hmacSecret)
    val badToken = token + "corrupted"

    val parts = Jwt.parse(badToken).toOption.get
    val result = Jwt.verifyHs256(parts, hmacSecret)
    assert(result.isLeft)

  test("RS256 roundtrip minting and verification with KeyPair"):
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()

    val header = Json.obj("alg" -> "RS256".asJson, "typ" -> "JWT".asJson, "kid" -> "test-key-1".asJson)
    val payload = Json.obj(
      "iss" -> "https://keycloak.example".asJson,
      "sub" -> "service-account".asJson,
      "aud" -> List("https://mcp.example").asJson,
      "scope" -> "mcp:read".asJson
    )

    val token = Jwt.signRs256(header, payload, kp.getPrivate)
    val parts = Jwt.parse(token).toOption.get
    assertEquals(parts.algorithm, "RS256")
    assertEquals(parts.keyId, Some("test-key-1"))

    val claims = Jwt.verifyRs256(parts, kp.getPublic).toOption.get
    assertEquals(claims.iss, "https://keycloak.example")
    assertEquals(claims.sub, Some("service-account"))
    assertEquals(claims.aud, List("https://mcp.example"))
    assertEquals(claims.scopes, Set("mcp:read"))

  test("RS256 rejects verification with wrong public key"):
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp1 = kpg.generateKeyPair()
    val kp2 = kpg.generateKeyPair()

    val header = Json.obj("alg" -> "RS256".asJson, "typ" -> "JWT".asJson)
    val payload = Json.obj("iss" -> "https://test".asJson)
    val token = Jwt.signRs256(header, payload, kp1.getPrivate)

    val parts = Jwt.parse(token).toOption.get
    val result = Jwt.verifyRs256(parts, kp2.getPublic)
    assert(result.isLeft)

  test("rejects malformed token shape"):
    assert(Jwt.parse("not-a-jwt").isLeft)
    assert(Jwt.parse("a.b").isLeft)
    assert(Jwt.parse("a.b.c.d").isLeft)
