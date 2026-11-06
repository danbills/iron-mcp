package ironmcp
package auth

import cats.effect.IO
import io.circe.{Decoder, Json, parser}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import ironmcp.http.McpHttp
import ironmcp.protocol.Implementation
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8

class McpAuthServerSuite extends CatsEffectSuite:

  final case class VoidArgs(dummy: Option[String]) derives Decoder, JsonSchemaOf

  private val readSummary = McpTool[VoidArgs]("read_summary", "Reads summary report.")(_ =>
    IO.pure("Summary: Q2 revenue up 8%.")
  )

  private val exportFullData = McpTool[VoidArgs]("export_full_data", "Exports full dataset.")(_ =>
    IO.pure("export.csv: 4,812 rows.")
  )

  private val hmacSecret = "mcp-auth-server-suite-secret-hmac-256".getBytes(UTF_8)
  private val serverUrl = "http://localhost:5309"
  private val mcpResource = s"$serverUrl/mcp"

  private val metadata = ProtectedResourceMetadata(
    resource = mcpResource,
    authorization_servers = List("https://auth.example.com"),
    scopes_supported = Some(List("reports:read", "reports:export"))
  )

  private val scopePolicy = ToolScopePolicy.Builder.empty
    .require("read_summary", "reports:read")
    .require("export_full_data", "reports:read", "reports:export")
    .build

  private val config = McpAuthServer.Config(
    mcpConfig = McpHttp.Config(path = "/mcp"),
    serverUrl = serverUrl,
    metadata = metadata,
    scopePolicy = scopePolicy
  )

  private val validator = TokenValidator.hs256[IO](
    hmacSecret,
    TokenValidator.Config(expectedResource = Some(mcpResource))
  )

  private val server = McpServer[IO](
    info = Implementation(name = "reports-server", version = "1.0.0"),
    tools = Some(ToolSet.of(readSummary, exportFullData))
  )

  private val routes = McpAuthServer.routes(server, validator, config).orNotFound

  private def mintToken(aud: String, scopes: String): String =
    val header = Json.obj("alg" -> "HS256".asJson, "typ" -> "JWT".asJson)
    val now = System.currentTimeMillis() / 1000
    val payload = Json.obj(
      "iss" -> "https://auth.example.com".asJson,
      "sub" -> "alice".asJson,
      "aud" -> List(aud).asJson,
      "exp" -> (now + 3600).asJson,
      "scope" -> scopes.asJson
    )
    Jwt.signHs256(header, payload, hmacSecret)

  private val meta = """{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

  private def toolCallBody(name: String) =
    s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":{},"_meta":$meta}}"""

  test("RFC 9728: serves protected resource metadata at root and path-aware endpoints"):
    val reqRoot = Request[IO](Method.GET, uri"/.well-known/oauth-protected-resource")
    val reqPath = Request[IO](Method.GET, uri"/.well-known/oauth-protected-resource/mcp")

    for
      resRoot <- routes.run(reqRoot)
      bodyRoot <- resRoot.as[String]
      resPath <- routes.run(reqPath)
      bodyPath <- resPath.as[String]
    yield
      assertEquals(resRoot.status, Status.Ok)
      assertEquals(resPath.status, Status.Ok)
      val jsonRoot = parser.parse(bodyRoot).toOption.get
      assertEquals(jsonRoot.hcursor.get[String]("resource"), Right(mcpResource))
      assertEquals(jsonRoot.hcursor.get[List[String]]("authorization_servers"), Right(List("https://auth.example.com")))
      assertEquals(bodyRoot, bodyPath)

  test("Rung 1: missing token yields 401 with resource_metadata pointer"):
    val req = Request[IO](Method.POST, uri"/mcp")
      .withEntity(toolCallBody("read_summary"))
      .putHeaders(
        Header.Raw(CIString("MCP-Protocol-Version"), "2026-07-28"),
        Header.Raw(CIString("Mcp-Method"), "tools/call"),
        Header.Raw(CIString("Mcp-Name"), "read_summary"),
        Header.Raw(CIString("Host"), "localhost:5309")
      )

    routes.run(req).flatMap { res =>
      assertEquals(res.status, Status.Unauthorized)
      val wwwAuth = res.headers.get(CIString("WWW-Authenticate")).map(_.head.value).getOrElse("")
      assert(wwwAuth.contains("Bearer error=\"invalid_request\""))
      assert(wwwAuth.contains(s"resource_metadata=\"$serverUrl/.well-known/oauth-protected-resource/mcp\""))
      res.as[String].map { body =>
        val json = parser.parse(body).toOption.get
        assertEquals(json.hcursor.get[String]("error"), Right("invalid_request"))
      }
    }

  test("Rung 2: genuine token with wrong aud yields 401 explaining RFC 8707 audience mismatch"):
    val wrongAudToken = mintToken("https://other.service.example/mcp", "reports:read reports:export")
    val req = Request[IO](Method.POST, uri"/mcp")
      .withEntity(toolCallBody("read_summary"))
      .putHeaders(
        Header.Raw(CIString("Authorization"), s"Bearer $wrongAudToken"),
        Header.Raw(CIString("MCP-Protocol-Version"), "2026-07-28"),
        Header.Raw(CIString("Mcp-Method"), "tools/call"),
        Header.Raw(CIString("Mcp-Name"), "read_summary"),
        Header.Raw(CIString("Host"), "localhost:5309")
      )

    routes.run(req).flatMap { res =>
      assertEquals(res.status, Status.Unauthorized)
      val wwwAuth = res.headers.get(CIString("WWW-Authenticate")).map(_.head.value).getOrElse("")
      assert(wwwAuth.contains("Bearer error=\"invalid_token\""))
      assert(wwwAuth.contains("RFC 8707 resource indicator"))
      res.as[String].map { body =>
        val json = parser.parse(body).toOption.get
        assertEquals(json.hcursor.get[String]("error"), Right("invalid_token"))
        val desc = json.hcursor.get[String]("error_description").getOrElse("")
        assert(desc.contains("this is a token for a different resource"))
      }
    }

  test("Rung 3: right aud, insufficient scope yields 403 naming FULL required scopes"):
    val readOnlyToken = mintToken(mcpResource, "reports:read")
    val req = Request[IO](Method.POST, uri"/mcp")
      .withEntity(toolCallBody("export_full_data"))
      .putHeaders(
        Header.Raw(CIString("Authorization"), s"Bearer $readOnlyToken"),
        Header.Raw(CIString("MCP-Protocol-Version"), "2026-07-28"),
        Header.Raw(CIString("Mcp-Method"), "tools/call"),
        Header.Raw(CIString("Mcp-Name"), "export_full_data"),
        Header.Raw(CIString("Host"), "localhost:5309")
      )

    routes.run(req).flatMap { res =>
      assertEquals(res.status, Status.Forbidden)
      val wwwAuth = res.headers.get(CIString("WWW-Authenticate")).map(_.head.value).getOrElse("")
      assert(wwwAuth.contains("Bearer error=\"insufficient_scope\""))
      // Must name FULL required scopes, not just missing reports:export
      assert(wwwAuth.contains("scope=\"reports:read reports:export\"") || wwwAuth.contains("scope=\"reports:export reports:read\""))
      assert(wwwAuth.contains(s"resource_metadata=\"$serverUrl/.well-known/oauth-protected-resource/mcp\""))
      res.as[String].map { body =>
        val json = parser.parse(body).toOption.get
        assertEquals(json.hcursor.get[String]("error"), Right("insufficient_scope"))
      }
    }

  test("Rung 4: right aud, full scopes executes tool successfully"):
    val fullToken = mintToken(mcpResource, "reports:read reports:export")
    val req = Request[IO](Method.POST, uri"/mcp")
      .withEntity(toolCallBody("export_full_data"))
      .putHeaders(
        Header.Raw(CIString("Authorization"), s"Bearer $fullToken"),
        Header.Raw(CIString("MCP-Protocol-Version"), "2026-07-28"),
        Header.Raw(CIString("Mcp-Method"), "tools/call"),
        Header.Raw(CIString("Mcp-Name"), "export_full_data"),
        Header.Raw(CIString("Host"), "localhost:5309")
      )

    routes.run(req).flatMap { res =>
      assertEquals(res.status, Status.Ok)
      res.as[String].map { body =>
        val json = parser.parse(body).toOption.get
        val text = json.hcursor.downField("result").downField("content").downArray.get[String]("text")
        assertEquals(text, Right("export.csv: 4,812 rows."))
      }
    }
