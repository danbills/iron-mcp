package ironmcp

import cats.effect.IO
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.{McpSchema, McpStatelessServerTransport}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Allow, `Content-Type`}
import reactor.core.publisher.Mono

import java.util.concurrent.atomic.AtomicReference

/** A stateless Streamable HTTP transport on http4s.
  *
  * Stateless means exactly what it says: no session id, no SSE stream, no
  * server-held per-client state. One POST carries one JSON-RPC message and
  * gets one response, so any number of replicas can serve any request.
  *
  * The SDK's SPI is two methods wide, which is why implementing it directly
  * beats dragging in a servlet container.
  */
final class Http4sStatelessTransport private (
    endpoint: EndpointPath,
    mapper: McpJsonMapper,
    allowedOrigins: Set[String]
) extends McpStatelessServerTransport:

  private val handlerRef = new AtomicReference[McpStatelessServerHandler]()

  override def setMcpHandler(handler: McpStatelessServerHandler): Unit =
    handlerRef.set(handler)

  override def closeGracefully(): Mono[Void] =
    Mono.fromRunnable(() => handlerRef.set(null))

  private def matches(request: Request[IO]): Boolean =
    request.uri.path.renderString == (endpoint: String)

  /** DNS-rebinding protection: a browser page on another origin must not be
    * able to reach a locally bound MCP server. An absent Origin (a CLI client,
    * curl) is allowed; a present-and-unlisted one is not.
    */
  private def originAllowed(request: Request[IO]): Boolean =
    request.headers.get(org.typelevel.ci.CIString("Origin")).map(_.head.value) match
      case None         => true
      case Some(origin) => allowedOrigins.isEmpty || allowedOrigins.contains(origin)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case request @ POST -> _ if matches(request) =>
      if !originAllowed(request) then Forbidden(errorBody("origin not allowed"))
      else dispatch(request)

    // Stateless servers offer no SSE stream to open and no session to delete.
    case request @ (GET -> _) if matches(request)    => methodNotAllowed
    case request @ (DELETE -> _) if matches(request) => methodNotAllowed

  private val methodNotAllowed: IO[Response[IO]] =
    MethodNotAllowed(Allow(Set(Method.POST))).map(_.withEntity(errorBody("this endpoint is POST-only")))

  private def dispatch(request: Request[IO]): IO[Response[IO]] =
    for
      body     <- request.bodyText.compile.string
      handler  <- IO(handlerRef.get()).flatMap(h => IO.raiseUnless(h != null)(NotInitialized).as(h))
      context   = McpTransportContext.EMPTY
      response <- parse(body) match
                    case Left(why) => BadRequest(errorBody(why))
                    case Right(message: McpSchema.JSONRPCRequest) =>
                      ReactorInterop
                        .monoToIO(handler.handleRequest(context, message))
                        .flatMap:
                          case Some(reply) => json(IO(mapper.writeValueAsString(reply)))
                          case None        => Accepted()
                    case Right(message: McpSchema.JSONRPCNotification) =>
                      ReactorInterop.monoToIO(handler.handleNotification(context, message)) *> Accepted()
                    case Right(_) =>
                      // A stateless server never initiates a request, so it can
                      // never be the recipient of a client's response.
                      Accepted()
    yield response

  private def parse(body: String): Either[String, McpSchema.JSONRPCMessage] =
    try Right(McpSchema.deserializeJsonRpcMessage(mapper, body))
    catch case scala.util.control.NonFatal(e) => Left(s"malformed JSON-RPC message: ${e.getMessage}")

  private def json(payload: IO[String]): IO[Response[IO]] =
    payload.flatMap(Ok(_)).map(_.withContentType(`Content-Type`(MediaType.application.json)))

  private def errorBody(message: String): String =
    io.circe.Json
      .obj(
        "jsonrpc" -> io.circe.Json.fromString("2.0"),
        "id"      -> io.circe.Json.Null,
        "error" -> io.circe.Json.obj(
          "code"    -> io.circe.Json.fromInt(-32600),
          "message" -> io.circe.Json.fromString(message)
        )
      )
      .noSpaces

object Http4sStatelessTransport:

  /** @param allowedOrigins empty means "accept any Origin header"; supply the
    *                       real origins when the server is reachable from a browser.
    */
  def apply(
      endpoint: EndpointPath,
      mapper: McpJsonMapper,
      allowedOrigins: Set[String] = Set.empty
  ): Http4sStatelessTransport =
    new Http4sStatelessTransport(endpoint, mapper, allowedOrigins)

case object NotInitialized extends RuntimeException("MCP handler not attached to transport")
