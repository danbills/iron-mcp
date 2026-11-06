package ironmcp
package auth

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator

class JwsSuite extends FunSuite:
  import AuthFixtures.*

  private val input = "header.payload".getBytes(UTF_8)

  private val cases = List(
    (JwsAlgorithm.RS256, rsa),
    (JwsAlgorithm.RS384, rsa),
    (JwsAlgorithm.RS512, rsa),
    (JwsAlgorithm.PS256, rsa),
    (JwsAlgorithm.PS384, rsa),
    (JwsAlgorithm.PS512, rsa),
    (JwsAlgorithm.ES256, ec),
    (JwsAlgorithm.EdDSA, ed)
  )

  cases.foreach { (alg, kp) =>
    test(s"${alg.name}: signs and verifies; a changed byte fails"):
      val sig = Jws.sign(alg, kp.getPrivate, input)
      assert(Jws.verify(alg, kp.getPublic, input, sig))
      assert(!Jws.verify(alg, kp.getPublic, "header.payloaD".getBytes(UTF_8), sig))
      val flipped = sig.clone(); flipped(flipped.length / 2) = (flipped(flipped.length / 2) ^ 1).toByte
      assert(!Jws.verify(alg, kp.getPublic, input, flipped))
  }

  test("ES256 signatures are the 64-byte JOSE form, not DER"):
    assertEquals(Jws.sign(JwsAlgorithm.ES256, ec.getPrivate, input).length, 64)

  test("ECDSA with R or S zero is refused (CVE-2022-21449, psychic signatures)"):
    assert(!Jws.verify(JwsAlgorithm.ES256, ec.getPublic, input, new Array[Byte](64)))

  test("a key of the wrong type or size never verifies"):
    val sig = Jws.sign(JwsAlgorithm.RS256, rsa.getPrivate, input)
    assert(!Jws.verify(JwsAlgorithm.ES256, rsa.getPublic, input, sig))
    assert(!Jws.verify(JwsAlgorithm.HS256, rsa.getPublic, input, sig))
    val small = KeyPairGenerator.getInstance("RSA")
    small.initialize(1024)
    val weak = small.generateKeyPair()
    assert(Jws.keyFits(JwsAlgorithm.RS256, weak.getPublic).isLeft)
    assert(Jws.keyFits(JwsAlgorithm.HS256, Jwt.hmacKey("short".getBytes(UTF_8))).isLeft)

  test("parse refuses padding, extra parts, crit, a non-object payload and oversize tokens"):
    val good = hs(claims())
    assert(Jwt.parse(good).isRight)
    assert(Jwt.parse(good + "=").isLeft)
    assert(Jwt.parse(good + ".x").isLeft)
    assert(Jwt.parse(good.split('.').take(2).mkString(".") + ".").isLeft)
    val crit = Jwt.sign(JwsAlgorithm.HS256, Jwt.hmacKey(hmacSecret), JsonObject("crit" -> List("exp").asJson), claims())
    assert(Jwt.parse(crit).isLeft)
    val array = Jwt.sign(JwsAlgorithm.HS256, Jwt.hmacKey(hmacSecret), JsonObject.empty, Json.arr())
    assert(Jwt.parse(array).isLeft)
    assertEquals(Jwt.parse("a" * 9000).left.map(_.kind), Left("too_large"))

  test("an algorithm outside the allowlist is refused before any key is used, none above all"):
    val none = s"${Jwt.base64UrlEncode("""{"alg":"none"}""".getBytes(UTF_8))}.${Jwt.base64UrlEncode(claims().noSpaces.getBytes(UTF_8))}."
    val parts = Jwt.parse(none)
    assert(parts.flatMap(Jwt.algorithm(_, JwsAlgorithm.values.toSet)).isLeft)
