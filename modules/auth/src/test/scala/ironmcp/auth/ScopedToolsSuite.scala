package ironmcp
package auth

import cats.effect.IO
import io.circe.{Decoder, Json, parser}
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.Implementation
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8

class ScopedToolsSuite extends CatsEffectSuite:

  final case class NoArgs(dummy: Option[String]) derives Decoder, JsonSchemaOf

  private val tools = ToolSet.of[IO](
    McpTool[NoArgs]("read_summary", "Reads summary report.")(_ => IO.pure("Summary.")),
    McpTool[NoArgs]("export_full_data", "Exports full dataset.")(_ => IO.pure("export.csv"))
  )

  private val secret   = "scoped-tools-suite-secret-hmac-256-bit".getBytes(UTF_8)
  private val resource = "http://localhost:5309/mcp"

  private val config = McpAuthServer.Config(
    ResourceIdentity.unsafe(resource),
    List("https://auth.example.com"),
    AuthPolicy()
      .tool("read_summary", "reports:read")
      .tool("export_full_data", "reports:read", "reports:export")
  )

  private val validator = TokenValidator.sharedSecret[IO](
    secret,
    TokenValidator.Config("https://auth.example.com", resource, algorithms = Set(JwsAlgorithm.HS256))
  )

  private val routes = McpAuthServer
    .routesFor(validator, config) { (_, claims) =>
      IO.pure(Right(McpServer[IO](Implementation("reports", "1.0.0"), tools = Some(ScopedTools(tools, config.policy, Some(claims))))))
    }
    .orNotFound

  private def token(scopes: String): String =
    val now = System.currentTimeMillis() / 1000
    Jwt.signHs256(
      Json.obj("alg" -> "HS256".asJson, "typ" -> "at+jwt".asJson),
      Json.obj(
        "iss"   -> "https://auth.example.com".asJson,
        "aud"   -> List(resource).asJson,
        "exp"   -> (now + 3600).asJson,
        "scope" -> scopes.asJson
      ),
      secret
    )

  private val meta = """{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

  private def post(scopes: String, method: String, params: String, name: Option[String]): IO[Response[IO]] =
    routes.run(
      Request[IO](Method.POST, uri"/mcp")
        .withEntity(s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        .putHeaders(
          Header.Raw(CIString("Authorization"), s"Bearer ${token(scopes)}"),
          Header.Raw(CIString("MCP-Protocol-Version"), "2026-07-28"),
          Header.Raw(CIString("Mcp-Method"), method),
          Header.Raw(CIString("Host"), "localhost:5309")
        )
        .putHeaders(name.map(n => Header.Raw(CIString("Mcp-Name"), ironmcp.http.McpHeaders.encode(n))).toList)
    )

  private def listed(scopes: String): IO[(List[String], String)] =
    post(scopes, "tools/list", s"""{"_meta":$meta}""", None).flatMap(_.as[String]).map { body =>
      val result = parser.parse(body).toOption.get.hcursor.downField("result")
      (result.downField("tools").as[List[Json]].toOption.get.flatMap(_.hcursor.get[String]("name").toOption), result.get[String]("cacheScope").toOption.get)
    }

  test("tools/list shows only the tools the token's scopes allow, and is private to the caller"):
    for
      (narrow, scope) <- listed("reports:read")
      (wide, _)       <- listed("reports:read reports:export")
    yield
      assertEquals(narrow, List("read_summary"))
      assertEquals(scope, "private")
      assertEquals(wide, List("read_summary", "export_full_data"))

  test("a hidden tool is still refused with 403 insufficient_scope when called"):
    post("reports:read", "tools/call", s"""{"name":"export_full_data","arguments":{},"_meta":$meta}""", Some("export_full_data")).map { res =>
      assertEquals(res.status, Status.Forbidden)
      assert(res.headers.get(CIString("WWW-Authenticate")).exists(_.head.value.contains("insufficient_scope")))
    }

  test("the provider refuses a call itself, as a second check behind the HTTP layer"):
    val claims = TokenClaims("https://auth.example.com", Some("alice"), List(resource), exp = Long.MaxValue, scope = Some("reports:read"))
    val scoped = ScopedTools(tools, config.policy, Some(claims))
    val meta = ironmcp.protocol.RequestMeta(ironmcp.protocol.LatestProtocolVersion, ironmcp.protocol.ClientCapabilities())
    scoped.call(ironmcp.protocol.CallToolParams("export_full_data", meta)).map {
      case r: ironmcp.protocol.CallToolResult => assertEquals(r.isError, Some(true))
      case other                              => fail(other.toString)
    }
