package ironmcp
package auth

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

class KeycloakAndScopedSuite extends CatsEffectSuite:

  test("KeycloakClient discover, clientCredentials, and refreshToken against mock HttpApp"):
    val realm = "http://localhost:8080/realms/iron-mcp"
    val app = HttpApp[IO] {
      case GET -> Root / "realms" / "iron-mcp" / ".well-known" / "openid-configuration" =>
        Ok(
          Json.obj(
            "issuer"                 -> realm.asJson,
            "token_endpoint"         -> s"$realm/protocol/openid-connect/token".asJson,
            "jwks_uri"               -> s"$realm/protocol/openid-connect/certs".asJson,
            "introspection_endpoint" -> s"$realm/protocol/openid-connect/token/introspect".asJson,
            "revocation_endpoint"    -> s"$realm/protocol/openid-connect/revoke".asJson
          )
        )
      case GET -> Root / "realms" / "mismatch" / ".well-known" / "openid-configuration" =>
        Ok(
          Json.obj(
            "issuer"         -> "http://localhost:8080/realms/other".asJson,
            "token_endpoint" -> s"$realm/token".asJson,
            "jwks_uri"       -> s"$realm/certs".asJson
          )
        )
      case POST -> Root / "realms" / "iron-mcp" / "protocol" / "openid-connect" / "token" =>
        Ok(
          Json.obj(
            "access_token" -> "tok-123".asJson,
            "token_type"   -> "Bearer".asJson,
            "expires_in"   -> 300.asJson
          )
        )
      case _ => NotFound()
    }
    val client   = Client.fromHttpApp(app)
    val kc       = KeycloakClient[IO](client, Uri.unsafeFromString(realm))
    val mismatch = KeycloakClient[IO](client, Uri.unsafeFromString("http://localhost:8080/realms/mismatch"))
    val insecure = KeycloakClient[IO](client, Uri.unsafeFromString("http://example.com/realms/iron-mcp"))
    val auth     = ClientAuth.SecretPost("cid", "csec")
    for
      oidc   <- kc.discover
      t1     <- kc.clientCredentials(oidc, auth, Set("mcp:read"), Some("https://mcp.example"))
      t2     <- kc.refreshToken(oidc, auth, "ref-1", Set("mcp:read"))
      badIss <- mismatch.discover.attempt
      badUrl <- insecure.discover.attempt
    yield
      assertEquals(oidc.issuer, realm)
      assertEquals(t1.access_token, "tok-123")
      assertEquals(t2.access_token, "tok-123")
      assert(badIss.left.exists(_.isInstanceOf[OAuthError.IssuerMismatch]))
      assert(badUrl.left.exists(_.isInstanceOf[OAuthError.Insecure]))

  test("ScopedTools, ScopedResources, and ScopedPrompts enforce AuthPolicy"):
    val toolOk = McpTool[IO]("ok_tool", "allowed")(_ => IO.pure(ToolOutput.text("yes")))
    val toolNo = McpTool[IO]("no_tool", "denied")(_ => IO.pure(ToolOutput.text("no")))
    val tools  = ToolSet.of(toolOk, toolNo)

    val resources = new ResourceProvider[IO]:
      def list(params: ListResourcesParams): IO[ListResourcesResult] =
        IO.pure(ListResourcesResult(List(Resource("file:///ok/a", "a"), Resource("file:///secret/b", "b"))))
      def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult] =
        IO.pure(ReadResourceResult(List(ResourceContents.Text(params.uri, "data"))))
      override def templates(params: ListResourcesParams): IO[ListResourceTemplatesResult] =
        IO.pure(ListResourceTemplatesResult(List(ResourceTemplate("file:///ok/{x}", "tpl"))))

    val prompts = new PromptProvider[IO]:
      def list(params: ListPromptsParams): IO[ListPromptsResult] =
        IO.pure(ListPromptsResult(List(Prompt("ok_prompt"), Prompt("no_prompt"))))
      def get(params: GetPromptParams): IO[GetPromptResult | InputRequiredResult] =
        IO.pure(GetPromptResult(messages = List(PromptMessage(Role.user, ContentBlock.Text("hi")))))

    val policy = AuthPolicy(
      tools = Map("ok_tool" -> Access.Public),
      resourcePrefixes = List("file:///ok/" -> Access.Public),
      prompts = Map("ok_prompt" -> Access.Public)
    )
    val meta = RequestMeta(LatestProtocolVersion)
    val st   = ScopedTools(tools, policy, None)
    val sr   = new ScopedResources(resources, policy, None)
    val sp   = new ScopedPrompts(prompts, policy, None)

    for
      tl  <- st.list(ListToolsParams(meta))
      tc1 <- st.call(CallToolParams("ok_tool", meta))
      tc2 <- st.call(CallToolParams("no_tool", meta))
      rl  <- sr.list(ListResourcesParams(meta))
      rt  <- sr.templates(ListResourcesParams(meta))
      rr1 <- sr.read(ReadResourceParams("file:///ok/a", meta))
      rr2 <- sr.read(ReadResourceParams("file:///secret/b", meta)).attempt
      pl  <- sp.list(ListPromptsParams(meta))
      pg1 <- sp.get(GetPromptParams("ok_prompt", meta))
      pg2 <- sp.get(GetPromptParams("no_prompt", meta)).attempt
    yield
      assertEquals(tl.tools.map(_.name: String), List("ok_tool"))
      assertEquals(tl.cacheScope, CacheScope.`private`)
      assertEquals(tc1.asInstanceOf[CallToolResult].isError, None)
      assertEquals(tc2.asInstanceOf[CallToolResult].isError, Some(true))
      assertEquals(rl.resources.map(_.uri: String), List("file:///ok/a"))
      assertEquals(rt.cacheScope, CacheScope.`private`)
      assert(rr1.isInstanceOf[ReadResourceResult])
      assert(rr2.isLeft)
      assertEquals(pl.prompts.map(_.name: String), List("ok_prompt"))
      assert(pg1.isInstanceOf[GetPromptResult])
      assert(pg2.isLeft)
