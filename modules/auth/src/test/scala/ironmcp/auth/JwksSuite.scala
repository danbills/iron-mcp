package ironmcp
package auth

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class JwksSuite extends CatsEffectSuite:

  test("Jwk converts RSA modulus and exponent to PublicKey"):
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()
    val rsaPub = kp.getPublic.asInstanceOf[RSAPublicKey]

    val nStr = Jwt.base64UrlEncode(rsaPub.getModulus.toByteArray)
    val eStr = Jwt.base64UrlEncode(rsaPub.getPublicExponent.toByteArray)

    val jwk = Jwk(
      kty = "RSA",
      alg = Some("RS256"),
      kid = Some("key-1"),
      n = Some(nStr),
      e = Some(eStr)
    )

    val pubKey = jwk.toPublicKey.toOption.get
    assertEquals(pubKey.getAlgorithm, "RSA")

    // Verify token signed by private key with this derived public key
    val header = Json.obj("alg" -> "RS256".asJson, "typ" -> "JWT".asJson, "kid" -> "key-1".asJson)
    val payload = Json.obj("iss" -> "https://auth".asJson, "sub" -> "alice".asJson)
    val token = Jwt.signRs256(header, payload, kp.getPrivate)

    val parts = Jwt.parse(token).toOption.get
    val claims = Jwt.verifyRs256(parts, pubKey).toOption.get
    assertEquals(claims.sub, Some("alice"))

  test("JwksProvider.fromJwks resolves key by kid"):
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()
    val rsaPub = kp.getPublic.asInstanceOf[RSAPublicKey]

    val jwk = Jwk(
      kty = "RSA",
      alg = Some("RS256"),
      kid = Some("active-key-id"),
      n = Some(Jwt.base64UrlEncode(rsaPub.getModulus.toByteArray)),
      e = Some(Jwt.base64UrlEncode(rsaPub.getPublicExponent.toByteArray))
    )
    val keySet = JwksKeySet(List(jwk))
    val provider = JwksProvider.fromJwks[IO](keySet)

    for
      found <- provider.key(Some("active-key-id"))
      missing <- provider.key(Some("unknown-key-id"))
    yield
      assert(found.isDefined)
      assert(missing.isEmpty)
