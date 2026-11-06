package ironmcp
package auth

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.implicits.*

import java.math.BigInteger
import scala.concurrent.duration.*

class JwksSuite extends CatsEffectSuite:
  import AuthFixtures.*

  test("RFC 7638 §3.1 thumbprint test vector"):
    val jwk = Jwk(
      kty = "RSA",
      e = Some("AQAB"),
      n = Some(
        "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw"
      )
    )
    assertEquals(jwk.thumbprint, Right("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"))

  test("RSA, EC and Ed25519 keys round-trip through JWK and verify their own signatures"):
    List((rsa, JwsAlgorithm.RS256), (ec, JwsAlgorithm.ES256), (ed, JwsAlgorithm.EdDSA)).foreach { (kp, alg) =>
      val key = vkey(kp, "k")
      assert(key.fits(alg), alg.name)
      val sig = Jws.sign(alg, kp.getPrivate, "x".getBytes)
      assert(Jws.verify(alg, key.key, "x".getBytes, sig))
    }

  test("keys that may not verify signatures are dropped from a JWKS"):
    val good = Jwk.fromPublicKey(rsa.getPublic, Some("sig")).toOption.get
    val set = JwksKeySet(
      List(
        good,
        good.copy(kid = Some("enc"), use = Some("enc")),
        good.copy(kid = Some("ops"), key_ops = Some(List("encrypt"))),
        good.copy(kid = Some("priv"), d = Some("AQAB")),
        good.copy(kid = Some("oaep"), alg = Some("RSA-OAEP")),
        good.copy(kid = Some("weak"), n = Some(Jwt.base64UrlEncode(BigInteger.ONE.shiftLeft(1023).toByteArray)))
      )
    )
    assertEquals(JwksKeySet.usable(set).keys.flatMap(_.kid), List("sig"))

  test("an EC point that is not on its curve is refused"):
    val jwk = Jwk.fromPublicKey(ec.getPublic, Some("k")).toOption.get
    val badY = Jwt.base64UrlEncode(Array.fill[Byte](32)(7))
    assert(jwk.copy(y = Some(badY)).toPublicKey.isLeft)

  test("without a kid, a token is matched only if exactly one key fits"):
    val one = KeySet.of(vkey(rsa, "a"))
    val two = KeySet.of(vkey(rsa, "a"), vkey(rsa2, "b"))
    assert(one.select(None, JwsAlgorithm.RS256).isRight)
    assertEquals(two.select(None, JwsAlgorithm.RS256).map(_.kid), Left(KeySet.Miss.Ambiguous))
    assertEquals(two.select(Some("b"), JwsAlgorithm.RS256).map(_.kid), Right(Some("b")))
    assertEquals(two.select(Some("b"), JwsAlgorithm.ES256).map(_.kid), Left(KeySet.Miss.Unfit))

  /** A JWKS endpoint that counts its fetches and can be switched off. */
  private def server(keys: Ref[IO, Json], fetches: Ref[IO, Int], down: Ref[IO, Boolean]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { _ =>
      fetches.update(_ + 1) *> down.get.flatMap {
        case true  => IO.pure(Response[IO](Status.InternalServerError))
        case false => keys.get.map(k => Response[IO](Status.Ok).withEntity(k))
      }
    })

  private def jwks(kps: (java.security.KeyPair, String)*): Json =
    Json.obj("keys" -> kps.map((kp, kid) => Jwk.fromPublicKey(kp.getPublic, Some(kid)).toOption.get).asJson)

  private val settings = JwksProvider.Settings(minRefetchInterval = 1.hour)

  test("remote: a flood of unknown kids costs one fetch, not one per token"):
    for
      keys    <- Ref.of[IO, Json](jwks(rsa -> "a"))
      fetches <- Ref.of[IO, Int](0)
      down    <- Ref.of[IO, Boolean](false)
      p       <- JwksProvider.remote[IO](server(keys, fetches, down), uri"http://localhost/certs", settings)
      _       <- p.select(Some("a"), JwsAlgorithm.RS256)
      results <- (1 to 50).toList.parTraverse(i => p.select(Some(s"bogus-$i"), JwsAlgorithm.RS256))
      count   <- fetches.get
    yield
      assert(results.forall(_.isLeft))
      assertEquals(count, 1) // the first lookup; every unknown kid after it is inside the cooldown

  test("remote: concurrent first lookups share one fetch"):
    for
      keys    <- Ref.of[IO, Json](jwks(rsa -> "a"))
      fetches <- Ref.of[IO, Int](0)
      down    <- Ref.of[IO, Boolean](false)
      p       <- JwksProvider.remote[IO](server(keys, fetches, down), uri"http://localhost/certs", settings)
      results <- (1 to 20).toList.parTraverse(_ => p.select(Some("a"), JwsAlgorithm.RS256))
      count   <- fetches.get
    yield
      assert(results.forall(_.isRight))
      assertEquals(count, 1)

  test("remote: a rotated key is picked up once the cooldown allows"):
    for
      keys    <- Ref.of[IO, Json](jwks(rsa -> "a"))
      fetches <- Ref.of[IO, Int](0)
      down    <- Ref.of[IO, Boolean](false)
      p       <- JwksProvider.remote[IO](server(keys, fetches, down), uri"http://localhost/certs", JwksProvider.Settings(minRefetchInterval = Duration.Zero))
      _       <- p.select(Some("a"), JwsAlgorithm.RS256)
      _       <- keys.set(jwks(rsa -> "a", rsa2 -> "b"))
      found   <- p.select(Some("b"), JwsAlgorithm.RS256)
    yield assert(found.isRight)

  test("remote: unreachable with nothing cached is KeysUnavailable, never a pass"):
    for
      keys    <- Ref.of[IO, Json](jwks(rsa -> "a"))
      fetches <- Ref.of[IO, Int](0)
      down    <- Ref.of[IO, Boolean](true)
      p       <- JwksProvider.remote[IO](server(keys, fetches, down), uri"http://localhost/certs", settings)
      result  <- p.select(Some("a"), JwsAlgorithm.RS256)
    yield assertEquals(result.left.map(_.kind), Left("keys_unavailable"))

  test("remote: refuses a non-https JWKS URL off loopback"):
    JwksProvider.remote[IO](Client.fromHttpApp(HttpApp.notFound[IO]), uri"http://keys.example.com/certs").attempt.map { r =>
      assert(r.isLeft)
    }
