package ironmcp
package auth

import cats.effect.{IO, Ref}
import io.circe.{Decoder, Json, parser}
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.{CacheScope, Implementation, ListResourcesParams, ListResourcesResult, ReadResourceParams, ReadResourceResult, ResourceContents}
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

/** The resource server's ladder, end to end over real wire JSON. */
class McpAuthServerSuite extends CatsEffectSuite:
  import AuthFixtures.*

  final case class NoArgs(dummy: Option[String]) derives Decoder, JsonSchemaOf

  private val hello   = McpTool[NoArgs]("hello", "Says hello.")(_ => IO.pure("hello")).public
  private val whoami  = McpTool[NoArgs]("whoami", "Who is calling.")(_ => IO.pure("you")).signedIn
  private val summary = McpTool[NoArgs]("read_summary", "Reads summary.")(_ => IO.pure("Summary.")).requires("reports:read")
  private val exporter = McpTool[NoArgs]("export_full_data", "Exports.")(_ => IO.pure("export.csv")).requires("reports:read", "reports:export")
  private val secret  = McpTool[NoArgs]("forgotten", "No rule.")(_ => IO.pure("leak"))
  private val all     = List(hello, whoami, summary, exporter, secret)

  private val policy = AuthPolicy
    .fromTools(all*)
    .resource("file:///public/", Access.Public)
    .resource("file:///reports/", Access.Scopes(Set("reports:read")))

  private val config = McpAuthServer.Config(resource, List(issuer), policy)

  private object Res extends ResourceProvider[IO]:
    def list(params: ListResourcesParams) = IO.pure(ListResourcesResult(Nil, 60000L, CacheScope.`private`))
    def read(params: ReadResourceParams) =
      IO.pure(ReadResourceResult(List(ResourceContents.Text(params.uri, "contents")), 60000L, CacheScope.`private`))

  private def serverFor(caller: Option[TokenClaims]) =
    McpServer[IO](
      Implementation("reports", "1.0.0"),
      tools = Some(ScopedTools(ToolSet(all), policy, caller)),
      resources = Some(new ScopedResources(Res, policy, caller))
    )

  private def strict(audit: AuthAudit[IO] = AuthAudit.noop[IO]) =
    McpAuthServer.routesFor(hsValidator, config, audit)((_, claims) => IO.pure(Right(serverFor(Some(claims))))).orNotFound

  private val optional =
    McpAuthServer.routesForOptional(hsValidator, config.copy(policy = policy.publicListing))((_, caller) => IO.pure(Right(serverFor(caller)))).orNotFound

  private def call(tool: String, token: Option[String], app: HttpApp[IO] = strict()) = app.run(mcp("tools/call", Some(tool), token))

  private def text(res: Response[IO]): IO[Option[String]] =
    res.as[String].map(b => parser.parse(b).toOption.flatMap(_.hcursor.downField("result").downField("content").downArray.get[String]("text").toOption))

  test("ResourceIdentity puts the well-known segment before the path (RFC 9728 §3.1)"):
    assertEquals(resource.metadataUrl, "http://localhost:5309/.well-known/oauth-protected-resource/mcp")
    assertEquals(ResourceIdentity.unsafe("https://Example.COM:443/a/mcp/").resource, "https://example.com/a/mcp")
    assert(ResourceIdentity.from("http://example.com/mcp").isLeft)
    assert(ResourceIdentity.from("https://example.com/mcp?x=1").isLeft)

  test("metadata names this resource, the issuer and the policy's scopes"):
    strict().run(Request[IO](Method.GET, uri"/.well-known/oauth-protected-resource/mcp")).flatMap(_.as[String]).map { body =>
      val c = parser.parse(body).toOption.get.hcursor
      assertEquals(c.get[String]("resource"), Right(audience))
      assertEquals(c.get[List[String]]("authorization_servers"), Right(List(issuer)))
      assertEquals(c.get[List[String]]("scopes_supported"), Right(List("reports:export", "reports:read")))
    }

  test("no token: 401 with resource_metadata and the scopes to ask for, and no error code"):
    call("read_summary", None).map { res =>
      assertEquals(res.status, Status.Unauthorized)
      val c = AuthChallenge.parse(List(challenge(res))).head
      assertEquals(c.param("resource_metadata"), Some(resource.metadataUrl))
      assertEquals(c.scopes, Set("reports:read"))
      assertEquals(c.error, None)
    }

  test("wrong audience: 401 invalid_token with a fixed description that echoes nothing"):
    call("read_summary", Some(hs(claims(scope = "reports:read", aud = Json.fromString("https://other.example/mcp"))))).map { res =>
      assertEquals(res.status, Status.Unauthorized)
      val c = AuthChallenge.parse(List(challenge(res))).head
      assertEquals(c.error, Some("invalid_token"))
      assertEquals(c.param("error_description"), Some(AuthError.WrongAudience("", Nil).publicDescription))
      assert(!challenge(res).contains("other.example"))
    }

  test("insufficient scope: 403 naming the full required set"):
    call("export_full_data", Some(hs(claims(scope = "reports:read")))).map { res =>
      assertEquals(res.status, Status.Forbidden)
      val c = AuthChallenge.parse(List(challenge(res))).head
      assertEquals(c.error, Some("insufficient_scope"))
      assertEquals(c.scopes, Set("reports:read", "reports:export"))
    }

  test("full scopes: the tool runs"):
    call("export_full_data", Some(hs(claims(scope = "reports:read reports:export")))).flatMap(text).map(t => assertEquals(t, Some("export.csv")))

  test("deny by default: a tool with no rule is refused even to a token with every scope"):
    call("forgotten", Some(hs(claims(scope = "reports:read reports:export")))).map { res =>
      assertEquals(res.status, Status.Forbidden)
      assert(!challenge(res).contains("scope="))
    }

  test("undeclared lists the tools nobody can call"):
    assertEquals(policy.undeclared(all*), List("forgotten"))

  test("tools/list shows only callable tools, privately"):
    strict().run(mcp("tools/list", token = Some(hs(claims(scope = "reports:read"))))).flatMap(_.as[String]).map { body =>
      val r = parser.parse(body).toOption.get.hcursor.downField("result")
      val names = r.downField("tools").as[List[Json]].toOption.get.flatMap(_.hcursor.get[String]("name").toOption)
      assertEquals(names, List("hello", "whoami", "read_summary"))
      assertEquals(r.get[String]("cacheScope"), Right("private"))
    }

  test("resources/read is gated by URI prefix; unlisted URIs are refused"):
    val reader = Some(hs(claims(scope = "reports:read")))
    val nobody = Some(hs(claims()))
    for
      ok     <- strict().run(mcp("resources/read", Some("file:///reports/q2.csv"), reader))
      denied <- strict().run(mcp("resources/read", Some("file:///reports/q2.csv"), nobody))
      other  <- strict().run(mcp("resources/read", Some("file:///etc/passwd"), reader))
    yield
      assertEquals(ok.status, Status.Ok)
      assertEquals(denied.status, Status.Forbidden)
      assertEquals(other.status, Status.Forbidden)

  test("strict mode: even a public tool needs a token"):
    call("hello", None).map(res => assertEquals(res.status, Status.Unauthorized))

  test("optional sign-in: public tools and listings work anonymously; protected ones start sign-in"):
    for
      pub  <- call("hello", None, optional).flatMap(text)
      list <- optional.run(mcp("tools/list")).flatMap(_.as[String])
      prot <- call("read_summary", None, optional)
      who  <- call("whoami", None, optional)
    yield
      assertEquals(pub, Some("hello"))
      val names = parser.parse(list).toOption.get.hcursor.downField("result").downField("tools").as[List[Json]].toOption.get
        .flatMap(_.hcursor.get[String]("name").toOption)
      assertEquals(names, List("hello"))
      assertEquals(prot.status, Status.Unauthorized)
      assertEquals(who.status, Status.Unauthorized)

  test("optional sign-in: an invalid token is refused, never downgraded to anonymous"):
    call("hello", Some(hs(claims(exp = Some(now - 999)))), optional).map(res => assertEquals(res.status, Status.Unauthorized))

  test("a repeated Authorization header, a malformed token or a token in the query is a 400"):
    val good = hs(claims(scope = "reports:read"))
    val twice = mcp("tools/call", Some("read_summary"), Some(good)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $good"))
    val bad = mcp("tools/call", Some("read_summary"), authorization = Some("Bearer a\"b"))
    val query = mcp("tools/call", Some("read_summary"), Some(good), path = uri"/mcp?access_token=x")
    for
      a <- strict().run(Request[IO](twice.method, twice.uri, headers = Headers(twice.headers.headers ++ List(Header.Raw(CIString("Authorization"), s"Bearer $good"))), body = twice.body))
      b <- strict().run(bad)
      c <- strict().run(query)
    yield
      assertEquals(a.status, Status.BadRequest)
      assertEquals(b.status, Status.BadRequest)
      assert(c.status == Status.BadRequest || c.status == Status.NotFound) // the query does not match /mcp's path either way

  test("the scheme is case-insensitive"):
    strict().run(mcp("tools/call", Some("read_summary"), authorization = Some(s"bearer ${hs(claims(scope = "reports:read"))}"))).map(r => assertEquals(r.status, Status.Ok))

  test("a header naming one tool cannot call another"):
    val token = hs(claims(scope = "reports:read"))
    val lying = mcp("tools/call", Some("export_full_data"), Some(token)).putHeaders(Header.Raw(CIString("Mcp-Name"), ironmcp.http.McpHeaders.encode("read_summary")))
    strict().run(lying).map(r => assert(r.status != Status.Ok, r.status.toString))

  test("quoted-strings cannot be broken out of"):
    val rendered = AuthChallenge("Bearer", Map("error_description" -> "a\"b\r\nX-Injected: 1\\")).render
    assert(!rendered.contains("\r") && !rendered.contains("\n"))
    assertEquals(AuthChallenge.parse(List(rendered)).head.param("error_description"), Some("a\"b??X-Injected: 1\\"))

  test("the audit trail records every decision, and never the token"):
    for
      events <- Ref.of[IO, List[String]](Nil)
      app     = strict(AuthAudit.jsonLines(line => events.update(_ :+ line)))
      token   = hs(claims(scope = "reports:read", extra = List("jti" -> "t-1".asJson)))
      _      <- app.run(mcp("tools/call", Some("read_summary"), Some(token)))
      _      <- app.run(mcp("tools/call", Some("export_full_data"), Some(token)))
      _      <- app.run(mcp("tools/call", Some("read_summary"), None))
      lines  <- events.get
    yield
      val parsed = lines.map(l => parser.parse(l).toOption.get.hcursor)
      assertEquals(parsed.map(_.get[Boolean]("allowed").toOption.get), List(true, false, false))
      assertEquals(parsed.map(_.get[String]("reason").toOption.get), List("ok", "insufficient_scope", "missing_token"))
      assertEquals(parsed.head.get[String]("subject"), Right("alice"))
      assertEquals(parsed.head.get[String]("jti"), Right("t-1"))
      assert(lines.forall(!_.contains(token)))

  test("views(): ui:// resources are readable without a token, other resources still are not"):
    val withViews = McpAuthServer.Config(resource, List(issuer), policy.views().publicListing)
    val app = McpAuthServer.routesForOptional(hsValidator, withViews)((_, caller) => IO.pure(Right(serverFor(caller)))).orNotFound
    for
      view  <- app.run(mcp("resources/read", Some("ui://reports/app.html")))
      other <- app.run(mcp("resources/read", Some("file:///reports/q2.csv")))
    yield
      assertEquals(view.status, Status.Ok)
      assertEquals(other.status, Status.Unauthorized)
