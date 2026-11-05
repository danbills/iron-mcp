package ironmcp
package http

import cats.effect.{IO, Ref}
import io.circe.{Decoder, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.{McpClient, McpClientError}
import ironmcp.protocol.{
  CacheScope, CallToolResult, ContentBlock, ErrorCode, Implementation, InputRequiredResult, JsonRpcMessage,
  ListResourcesParams, ListResourcesResult, ReadResourceParams, ReadResourceResult, ResourceContents
}
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.typelevel.ci.CIString

/** The HTTP client against the real server routes, in memory. The server checks
  * every mirrored header against the body, so a call that succeeds proves the
  * client sent the right ones.
  */
class HttpClientSuite extends CatsEffectSuite:

  final case class EchoArgs(message: String :| Not[Empty]) derives Decoder, JsonSchemaOf

  private val resources = new ResourceProvider[IO]:
    def list(params: ListResourcesParams): IO[ListResourcesResult] = IO.pure(ListResourcesResult(Nil, 0L, CacheScope.`private`))
    def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult] =
      IO.pure(ReadResourceResult(List(ResourceContents.Text(params.uri, s"read ${params.uri: String}")), 0L, CacheScope.`private`))

  private val server = McpServer[IO](
    info = Implementation(name = "http-client-suite", version = "0.1.0"),
    tools = Some(ToolSet.of(McpTool[EchoArgs]("echo", "Echoes.")(args => IO.pure(s"Echo: ${args.message: String}")))),
    resources = Some(resources)
  )

  private val endpoint = uri"http://localhost/mcp"

  private def connect(app: HttpApp[IO], headers: IO[Headers] = IO.pure(Headers.empty)) =
    McpClient[IO](McpHttpClient.transport(Client.fromHttpApp(app), endpoint, headers, _ => IO.unit))

  private def text(result: CallToolResult | InputRequiredResult) = result match
    case r: CallToolResult => r.content.collectFirst { case ContentBlock.Text(t, _, _) => t }.getOrElse("")
    case other             => fail(s"unexpected $other")

  test("the spec's encoding examples for header values"):
    assertEquals(McpHeaders.encode("us-west1"), "us-west1")
    assertEquals(McpHeaders.encode("Hello, 世界"), "=?base64?SGVsbG8sIOS4lueVjA==?=")
    assertEquals(McpHeaders.encode(" padded "), "=?base64?IHBhZGRlZCA=?=")
    assertEquals(McpHeaders.encode("line1\nline2"), "=?base64?bGluZTEKbGluZTI=?=")
    assertEquals(McpHeaders.encode("=?base64?literal?="), "=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=")
    assertEquals(McpHeaders.decode(McpHeaders.encode("Hello, 世界")), Some("Hello, 世界"))

  test("a tool call through the server's header checks"):
    connect(McpHttp.routes(server).orNotFound).flatMap(_.callTool("echo", JsonObject("message" -> "hi".asJson))).map { r =>
      assertEquals(text(r), "Echo: hi")
    }

  test("a non-ASCII resource URI travels base64-encoded in Mcp-Name"):
    connect(McpHttp.routes(server).orNotFound).flatMap(_.readResource("file:///notes/café.md")).map {
      case r: ReadResourceResult => assertEquals(r.contents, List(ResourceContents.Text("file:///notes/café.md", "read file:///notes/café.md")))
      case other                 => fail(s"unexpected $other")
    }

  test("a 404 with -32601 is an Rpc error, not an HTTP one"):
    connect(McpHttp.routes(server).orNotFound).flatMap(_.listPrompts().attempt).map {
      case Left(McpClientError.Rpc(error)) => assertEquals(error.code, ErrorCode.MethodNotFound)
      case other                           => fail(s"unexpected $other")
    }

  /** A resource server in miniature: 401 without a token, 403 for a token without the scope. */
  private val guarded = McpHttp.routesFor[IO](McpHttp.Config()) { request =>
    val challenge = (error: String, extra: String) =>
      Header.Raw(CIString("WWW-Authenticate"), s"""Bearer error="$error"$extra, resource_metadata="http://localhost/.well-known/oauth-protected-resource/mcp"""")
    IO.pure(request.headers.get(CIString("Authorization")).map(_.head.value) match
      case Some("Bearer admin") => Right(server)
      case Some("Bearer reader") => Left(Response[IO](Status.Forbidden).putHeaders(challenge("insufficient_scope", """, scope="reports:read reports:admin"""")))
      case _                     => Left(Response[IO](Status.Unauthorized).putHeaders(challenge("invalid_request", ""))))
  }.orNotFound

  test("401 and 403 come back as Http errors whose challenges parse"):
    for
      anonymous <- connect(guarded).flatMap(_.callTool("echo", JsonObject("message" -> "x".asJson)).attempt)
      reader    <- connect(guarded, IO.pure(Headers(Header.Raw(CIString("Authorization"), "Bearer reader")))).flatMap(_.callTool("echo").attempt)
    yield
      anonymous match
        case Left(e: McpClientError.Http) =>
          assertEquals(e.status, 401)
          assertEquals(McpHttpClient.challenges(e).flatMap(_.params.get("resource_metadata")),
            List("http://localhost/.well-known/oauth-protected-resource/mcp"))
        case other => fail(s"unexpected $other")
      reader match
        case Left(e: McpClientError.Http) =>
          assertEquals(e.status, 403)
          val bearer = McpHttpClient.challenges(e).head
          assertEquals(bearer.scheme, "Bearer")
          assertEquals(bearer.params.get("error"), Some("insufficient_scope"))
          assertEquals(bearer.params.get("scope"), Some("reports:read reports:admin"))
        case other => fail(s"unexpected $other")

  test("headers are evaluated per request, so a token can change between calls"):
    for
      token  <- Ref.of[IO, String]("reader")
      client <- connect(guarded, token.get.map(t => Headers(Header.Raw(CIString("Authorization"), s"Bearer $t"))))
      before <- client.callTool("echo", JsonObject("message" -> "x".asJson)).attempt
      _      <- token.set("admin")
      after  <- client.callTool("echo", JsonObject("message" -> "x".asJson))
    yield
      assert(before.isLeft)
      assertEquals(text(after), "Echo: x")

  test("an SSE reply: notifications go to the callback, then the response with our id"):
    val sse = HttpApp[IO] { _ =>
      val body =
        """event: message
          |data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"t","progress":0.5}}
          |
          |data: {"jsonrpc":"2.0","id":99,"result":{"content":[{"type":"text","text":"someone else's"}],"resultType":"complete"}}
          |
          |data: {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"streamed"}],"resultType":"complete"}}
          |
          |""".stripMargin
      IO.pure(Response[IO](Status.Ok).withEntity(body).withContentType(headers.`Content-Type`(MediaType.`text/event-stream`)))
    }
    for
      seen   <- Ref.of[IO, List[String]](Nil)
      client <- McpClient[IO](McpHttpClient.transport(Client.fromHttpApp(sse), endpoint, IO.pure(Headers.empty),
                  (n: JsonRpcMessage.Notification) => seen.update(_ :+ (n.method: String))))
      result <- client.callTool("anything")
      got    <- seen.get
    yield
      assertEquals(text(result), "streamed")
      assertEquals(got, List("notifications/progress"))

  test("a 5xx without a JSON-RPC body is an Http error"):
    connect(HttpApp[IO](_ => IO.pure(Response[IO](Status.BadGateway).withEntity("upstream down")))).flatMap(_.discover.attempt).map {
      case Left(McpClientError.Http(502, Nil, "upstream down")) => ()
      case other                                                 => fail(s"unexpected $other")
    }
