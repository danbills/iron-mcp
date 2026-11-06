package ironmcp
package auth

import cats.effect.IO
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.McpClient
import ironmcp.http.McpHttpClient
import ironmcp.protocol.{CallToolResult, Implementation}
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client

import java.nio.charset.StandardCharsets.UTF_8

class StepUpClientSuite extends CatsEffectSuite:

  final case class VoidArgs(dummy: Option[String]) derives Decoder, JsonSchemaOf

  private val readSummary = McpTool[VoidArgs]("read_summary", "Reads summary report.")(_ =>
    IO.pure("Summary: Q2 revenue up 8%.")
  )

  private val exportFullData = McpTool[VoidArgs]("export_full_data", "Exports full dataset.")(_ =>
    IO.pure("export.csv: 4,812 rows.")
  )

  private val hmacSecret = "stepup-client-suite-secret-hmac-256".getBytes(UTF_8)
  private val serverUrl = "http://localhost:5302"
  private val mcpResource = s"$serverUrl/mcp"

  private val authConfig = McpAuthServer.Config(
    ResourceIdentity.unsafe(mcpResource),
    List("http://localhost:5301"),
    AuthPolicy.fromTools(readSummary.requires("reports:read"), exportFullData.requires("reports:read", "reports:export"))
  )

  private val validator = TokenValidator.sharedSecret[IO](
    hmacSecret,
    TokenValidator.Config("http://localhost:5301", mcpResource, algorithms = Set(JwsAlgorithm.HS256))
  )

  private val server = McpServer[IO](
    info = Implementation(name = "reports-server", version = "1.0.0"),
    tools = Some(ToolSet.of(readSummary, exportFullData))
  )

  private val serverApp = McpAuthServer.routes(server, validator, authConfig).orNotFound
  private val httpClient = Client.fromHttpApp(serverApp)

  private def mintToken(scopes: Set[String]): String =
    val header = Json.obj("alg" -> "HS256".asJson, "typ" -> "at+jwt".asJson)
    val now = System.currentTimeMillis() / 1000
    val payload = Json.obj(
      "iss" -> "http://localhost:5301".asJson,
      "sub" -> "agent-stepup".asJson,
      "aud" -> List(mcpResource).asJson,
      "exp" -> (now + 3600).asJson,
      "scope" -> scopes.mkString(" ").asJson
    )
    Jwt.signHs256(header, payload, hmacSecret)

  test("The Scope-Union Rule: preserves previously held scopes and succeeds on step-up"):
    // Simulated AS that grants exactly what was requested
    def fakeAs(requested: Set[String]): IO[TokenResponse] = IO.pure(
      TokenResponse(
        access_token = mintToken(requested),
        token_type = "Bearer",
        expires_in = Some(3600),
        scope = Some(requested.mkString(" "))
      )
    )

    for
      stepUp <- StepUpClient.create[IO](fakeAs, strategy = ScopeStrategy.Union)
      _ <- stepUp.initialAuthorize(Set("reports:read"))
      transport = McpHttpClient.transport[IO](
        httpClient,
        Uri.unsafeFromString(mcpResource),
        stepUp.authHeaders,
        _ => IO.unit
      )
      client <- McpClient[IO](transport, Implementation(name = "test-agent", version = "1.0.0"))

      // [1] Call read_summary -> succeeds
      res1 <- stepUp.callTool(client, "read_summary")
      text1 = res1 match
        case r: CallToolResult =>
          r.content.head match
            case ironmcp.protocol.ContentBlock.Text(txt, _, _) => txt
            case _ => ""
        case _ => ""
      _ = assertEquals(text1, "Summary: Q2 revenue up 8%.")

      // [2] Call export_full_data -> 403 triggers step-up with UNION (reports:read + reports:export)
      res2 <- stepUp.callTool(client, "export_full_data")
      text2 = res2 match
        case r: CallToolResult =>
          r.content.head match
            case ironmcp.protocol.ContentBlock.Text(txt, _, _) => txt
            case _ => ""
        case _ => ""
      _ = assertEquals(text2, "export.csv: 4,812 rows.")

      // [3] Verify state held the union
      st <- stepUp.state
      _ = assertEquals(st.requestedScopes, Set("reports:read", "reports:export"))
      _ = assertEquals(st.grantedScopes, Set("reports:read", "reports:export"))

      // [4] Call read_summary with new token -> still works! Nothing lost!
      res3 <- stepUp.callTool(client, "read_summary")
      text3 = res3 match
        case r: CallToolResult =>
          r.content.head match
            case ironmcp.protocol.ContentBlock.Text(txt, _, _) => txt
            case _ => ""
        case _ => ""
      _ = assertEquals(text3, "Summary: Q2 revenue up 8%.")
    yield ()

  test("The --naive anti-pattern: regresses previously working tools by requesting only missing scopes"):
    def fakeAs(requested: Set[String]): IO[TokenResponse] = IO.pure(
      TokenResponse(
        access_token = mintToken(requested),
        token_type = "Bearer",
        expires_in = Some(3600),
        scope = Some(requested.mkString(" "))
      )
    )

    for
      stepUp <- StepUpClient.create[IO](fakeAs, strategy = ScopeStrategy.Naive, maxRetries = 1)
      _ <- stepUp.initialAuthorize(Set("reports:read"))
      transport = McpHttpClient.transport[IO](
        httpClient,
        Uri.unsafeFromString(mcpResource),
        stepUp.authHeaders,
        _ => IO.unit
      )
      client <- McpClient[IO](transport, Implementation(name = "test-agent", version = "1.0.0"))

      // [1] Call read_summary -> succeeds
      res1 <- stepUp.callTool(client, "read_summary")
      text1 = res1 match
        case r: CallToolResult =>
          r.content.head match
            case ironmcp.protocol.ContentBlock.Text(txt, _, _) => txt
            case _ => ""
        case _ => ""
      _ = assertEquals(text1, "Summary: Q2 revenue up 8%.")

      // [2] Call export_full_data -> naive requests ONLY missing (reports:export), dropping reports:read!
      // The retry still fails because export_full_data needs BOTH reports:read and reports:export!
      res2Attempt <- stepUp.callTool(client, "export_full_data").attempt
      _ = assert(res2Attempt.isLeft)

      // [3] And calling read_summary with the new token now FAILS because reports:read was lost!
      res3Attempt <- client.callTool("read_summary").attempt
      _ = res3Attempt match
        case Left(err: ironmcp.client.McpClientError.Http) =>
          assertEquals(err.status, 403)
          assert(err.wwwAuthenticate.exists(_.contains("error=\"insufficient_scope\"")))
          assert(err.wwwAuthenticate.exists(_.contains("scope=\"reports:read\"")))
        case other =>
          fail(s"Expected 403 insufficient_scope challenge, got $other")
    yield ()

  test("Narrower Grant detection: halts immediately when AS withholds requested scopes"):
    // Simulated AS where user declined 'reports:export', so AS grants only 'reports:read'
    def stingyAs(@annotation.unused requested: Set[String]): IO[TokenResponse] = IO.pure(
      TokenResponse(
        access_token = mintToken(Set("reports:read")),
        token_type = "Bearer",
        expires_in = Some(3600),
        scope = Some("reports:read")
      )
    )

    for
      stepUp <- StepUpClient.create[IO](stingyAs, strategy = ScopeStrategy.Union)
      _ <- stepUp.initialAuthorize(Set("reports:read"))
      transport = McpHttpClient.transport[IO](
        httpClient,
        Uri.unsafeFromString(mcpResource),
        stepUp.authHeaders,
        _ => IO.unit
      )
      client <- McpClient[IO](transport, Implementation(name = "test-agent", version = "1.0.0"))

      attempt <- stepUp.callTool(client, "export_full_data").attempt
      _ = attempt match
        case Left(ex: StepUpClient.NarrowerGrantException) =>
          assertEquals(ex.withheldScopes, Set("reports:export"))
          assertEquals(ex.grantedScopes, Set("reports:read"))
        case other =>
          fail(s"Expected NarrowerGrantException, got $other")
    yield ()
