package ironmcp
package auth

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.Authorization

class IntrospectionSuite extends CatsEffectSuite:
  import AuthFixtures.*

  /** An introspection endpoint that answers `answer` to an authenticated client, counting calls. */
  private def endpoint(answer: Ref[IO, Json], calls: Ref[IO, Int]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      val authed = req.headers.get[Authorization].collect { case Authorization(BasicCredentials(user, pass)) => (user, pass) }.contains(("rs", "s3cret"))
      if !authed then IO.pure(Response[IO](Status.Unauthorized).withEntity(Json.obj("error" -> "invalid_client".asJson)))
      else calls.update(_ + 1) *> answer.get.map(a => Response[IO](Status.Ok).withEntity(a))
    })

  private val cfg = Introspection.Config(issuer, audience, "http://localhost/introspect", ClientAuth.SecretBasic("rs", "s3cret"))

  private def active(scope: String) = claims(scope = scope).deepMerge(Json.obj("active" -> true.asJson))

  test("an active token passes with its claims; the answer is cached"):
    for
      answer <- Ref.of[IO, Json](active("mcp:read"))
      calls  <- Ref.of[IO, Int](0)
      v      <- Introspection[IO](endpoint(answer, calls), cfg)
      r1     <- v.validate("opaque-1")
      r2     <- v.validate("opaque-1")
      n      <- calls.get
    yield
      assertEquals(r1.map(_.scopes), Right(Set("mcp:read")))
      assertEquals(r2.map(_.sub), Right(Some("alice")))
      assertEquals(n, 1)

  test("inactive, wrong audience, and missing exp are refused"):
    for
      answer <- Ref.of[IO, Json](Json.obj("active" -> false.asJson))
      calls  <- Ref.of[IO, Int](0)
      v      <- Introspection[IO](endpoint(answer, calls), cfg)
      a      <- v.validate("t-a")
      _      <- answer.set(active("x").deepMerge(Json.obj("aud" -> "https://other.example".asJson)))
      b      <- v.validate("t-b")
      _      <- answer.set(Json.obj("active" -> true.asJson, "iss" -> issuer.asJson, "aud" -> audience.asJson))
      c      <- v.validate("t-c")
    yield
      assertEquals(a.left.map(_.kind), Left("inactive"))
      assertEquals(b.left.map(_.kind), Left("audience"))
      assertEquals(c.left.map(_.kind), Left("missing_claim"))

  test("an endpoint that refuses the resource server is an outage (503), not a pass, and is not cached"):
    for
      answer <- Ref.of[IO, Json](active("mcp:read"))
      calls  <- Ref.of[IO, Int](0)
      v      <- Introspection[IO](endpoint(answer, calls), cfg.copy(auth = ClientAuth.SecretBasic("rs", "wrong")))
      r      <- v.validate("t")
    yield assertEquals(r.left.map(_.kind), Left("keys_unavailable"))
