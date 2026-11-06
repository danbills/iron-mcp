package ironmcp
package auth

import cats.effect.IO
import io.circe.{Decoder, Json, JsonObject, parser}
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.Implementation
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.typelevel.ci.CIString

class DpopSuite extends CatsEffectSuite:
  import AuthFixtures.*

  private val htu = audience
  private val settings = Dpop.Settings()

  private def rawProof(key: Dpop.Key, claims: JsonObject, typ: String = Dpop.ProofType, jwk: Json = Json.Null): IO[String] =
    // A hand-built proof, to break one rule at a time.
    key.proof[IO]("POST", htu, Some("tok")).map { good =>
      val parts   = Jwt.parse(good).toOption.get
      val payload = parts.payload.asObject.get.deepMerge(claims)
      val header  = parts.header.add("typ", typ.asJson).add("jwk", if jwk.isNull then key.jwk.asJson else jwk)
      // Re-sign with a throwaway key only when the jwk is replaced; otherwise the
      // signature would not verify and hide the rule under test.
      header -> payload
    }.flatMap { (header, payload) =>
      IO(Jwt.sign(JwsAlgorithm.ES256, ecPrivate(key), header, Json.fromJsonObject(payload)))
    }

  // The tests sign with the key's private half through this backdoor-free route:
  // they generate their own key pair and wrap it.
  private lazy val pair = AuthFixtures.ec
  private lazy val key: Dpop.Key = Dpop.Key.of(pair, JwsAlgorithm.ES256).toOption.get
  private def ecPrivate(k: Dpop.Key) = { val _ = k; pair.getPrivate }

  private def now = System.currentTimeMillis() / 1000

  test("a fresh proof passes and names the key's thumbprint"):
    key.proof[IO]("POST", htu, Some("tok")).map { p =>
      assertEquals(Dpop.check(p, "POST", htu, Some("tok"), now, settings).map(_.thumbprint), Right(key.thumbprint))
    }

  test("each rule of RFC 9449 §4.3 refuses a proof that breaks it"):
    def refused(p: String, htm: String = "POST", url: String = htu, token: Option[String] = Some("tok")) =
      assert(Dpop.check(p, htm, url, token, now, settings).isLeft, p)
    for
      good    <- key.proof[IO]("POST", htu, Some("tok"))
      typ     <- rawProof(key, JsonObject.empty, typ = "JWT")
      old     <- rawProof(key, JsonObject("iat" -> (now - 3600).asJson))
      future  <- rawProof(key, JsonObject("iat" -> (now + 3600).asJson))
      noJti   <- rawProof(key, JsonObject("jti" -> Json.Null))
      priv    <- rawProof(key, JsonObject.empty, jwk = key.jwk.copy(d = Some("AAAA")).asJson)
      other   <- Dpop.Key.generate[IO]
      swapped <- rawProof(key, JsonObject.empty, jwk = other.jwk.asJson)
    yield
      refused(good, htm = "GET")
      refused(good, url = "http://localhost:5309/other")
      refused(good, token = Some("another-token"))
      refused(typ)
      refused(old)
      refused(future)
      refused(noJti)
      refused(priv)
      refused(swapped) // the jwk does not match the signing key
      assert(Dpop.check(good, "POST", "HTTP://LOCALHOST:5309/mcp?x=1", Some("tok"), now, settings).isRight, "htu compares normalized, without query")

  test("a proof is good once"):
    for
      cache <- ReplayCache.inMemory[IO]()
      p     <- key.proof[IO]("POST", htu, Some("tok"))
      first <- Dpop.verify(p, "POST", htu, Some("tok"), now, settings, cache)
      again <- Dpop.verify(p, "POST", htu, Some("tok"), now, settings, cache)
    yield
      assert(first.isRight)
      assertEquals(again.left.map(_.kind), Left("dpop_proof"))

  final case class NoArgs(dummy: Option[String]) derives Decoder, JsonSchemaOf

  private val tool = McpTool[NoArgs]("read_summary", "Reads.")(_ => IO.pure("summary")).requires("mcp:read")

  private def server(policy: DpopPolicy) =
    val config = McpAuthServer.Config(resource, List(issuer), AuthPolicy.fromTools(tool), dpop = policy)
    McpAuthServer.routes(McpServer[IO](Implementation("s", "1"), tools = Some(ToolSet.of(tool))), hsValidator, config).orNotFound

  private def bound(k: Dpop.Key) = hs(claims(scope = "mcp:read", extra = List("cnf" -> Json.obj("jkt" -> k.thumbprint.asJson))))

  private def request(scheme: String, token: String) =
    mcp("tools/call", Some("read_summary"), authorization = Some(s"$scheme $token")).withUri(uri"http://localhost:5309/mcp")

  test("a bound token with its key's proof is served; the client middleware adds the proof"):
    for
      http   <- Dpop.client[IO](Client.fromHttpApp(server(DpopPolicy.Allowed)), key)
      status <- http.run(request("DPoP", bound(key))).use(r => IO.pure(r.status))
    yield assertEquals(status, Status.Ok)

  test("a bound token sent as Bearer is refused: stealing it is not enough"):
    server(DpopPolicy.Allowed).run(request("Bearer", bound(key))).map { r =>
      assertEquals(r.status, Status.Unauthorized)
      assert(challenge(r).nonEmpty)
    }

  test("a stolen bound token with the thief's own key is refused"):
    for
      thief  <- Dpop.Key.generate[IO]
      http   <- Dpop.client[IO](Client.fromHttpApp(server(DpopPolicy.Allowed)), thief)
      result <- http.run(request("DPoP", bound(key))).use(r => r.as[String].map(b => (r.status, b)))
    yield
      assertEquals(result._1, Status.Unauthorized)
      assertEquals(parser.parse(result._2).toOption.flatMap(_.hcursor.get[String]("error").toOption), Some("invalid_dpop_proof"))

  test("a replayed request is refused"):
    val app = server(DpopPolicy.Allowed)
    for
      token <- IO.pure(bound(key))
      proof <- key.proof[IO]("POST", htu, Some(token))
      req    = request("DPoP", token).putHeaders(Header.Raw(Dpop.HeaderName, proof))
      first <- app.run(req)
      again <- app.run(request("DPoP", token).putHeaders(Header.Raw(Dpop.HeaderName, proof)))
    yield
      assertEquals(first.status, Status.Ok)
      assertEquals(again.status, Status.Unauthorized)

  test("DPoP required: an unbound Bearer token is refused with a DPoP challenge, and metadata says so"):
    for
      r    <- server(DpopPolicy.Required).run(request("Bearer", hs(claims(scope = "mcp:read"))))
      meta <- server(DpopPolicy.Required).run(Request[IO](Method.GET, uri"/.well-known/oauth-protected-resource/mcp")).flatMap(_.as[String])
    yield
      assertEquals(r.status, Status.Unauthorized)
      val parsed = AuthChallenge.parse(r.headers.get(CIString("WWW-Authenticate")).toList.flatMap(_.toList.map(_.value)))
      assertEquals(parsed.map(_.scheme), List("DPoP"))
      assert(parsed.head.param("algs").exists(_.contains("ES256")))
      val m = parser.parse(meta).toOption.get.hcursor
      assertEquals(m.get[Boolean]("dpop_bound_access_tokens_required"), Right(true))

  test("DPoP allowed: an unbound Bearer token still works, and challenges offer both schemes"):
    for
      ok  <- server(DpopPolicy.Allowed).run(request("Bearer", hs(claims(scope = "mcp:read"))))
      no  <- server(DpopPolicy.Allowed).run(mcp("tools/call", Some("read_summary")))
    yield
      assertEquals(ok.status, Status.Ok)
      assertEquals(AuthChallenge.parse(no.headers.get(CIString("WWW-Authenticate")).toList.flatMap(_.toList.map(_.value))).map(_.scheme), List("Bearer", "DPoP"))
