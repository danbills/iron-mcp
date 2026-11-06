package ironmcp
package http

import cats.data.{Kleisli, OptionT}
import cats.effect.{Async, Concurrent, Resource}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import com.comcast.ip4s.{Host, Port as IpPort}
import fs2.io.net.Network
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.ServerHandler
import ironmcp.transport.Wire
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.server.Server
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8

/** The Streamable HTTP binding of revision 2026-07-28, stateless.
  *
  * Every message is its own POST; a request gets one `application/json` reply,
  * a notification gets `202`. There are no sessions and no GET stream, so a GET
  * or DELETE is `405`, and `Mcp-Session-Id` / `Last-Event-ID` are ignored.
  *
  * A request that emits notifications while it runs (progress) is answered
  * with an SSE stream scoped to it instead, when the client's `Accept` allows
  * `text/event-stream`: the notifications as they happen, then the reply, then
  * the end of the stream. The choice is made by what happens first. If the
  * reply is ready before any notification, it goes out as plain JSON, with the
  * same status codes as always. Without `text/event-stream` in `Accept`,
  * notifications are dropped.
  *
  * The headers that mirror the body (`MCP-Protocol-Version`, `Mcp-Method`,
  * `Mcp-Name`) are required and must agree with it; a mismatch is `400` with
  * `-32020`, so a proxy routing on a header and this server reading the body
  * cannot be told two different things.
  *
  * `serverFor` builds the server for each request. That is where per-request
  * context such as an access token's claims gets in: close the tools over it.
  * `McpServer` holds no state, so building one per request costs nothing.
  */
object McpHttp:

  /** @param path         the MCP endpoint
    * @param allowOrigin  which `Origin` values may call; a browser page from
    *                     anywhere else gets `403` (DNS rebinding guard)
    * @param allowHost    which `Host` values are served, for the same reason
    * @param maxBodyBytes larger bodies get `413` before any parsing
    * @param cors         answer CORS for the origins `allowOrigin` accepts, so
    *                     a host page in a browser (on another port or origin)
    *                     can call: a preflight `OPTIONS` gets `204`, and replies
    *                     carry `Access-Control-Allow-Origin` and expose
    *                     `WWW-Authenticate` and `MCP-Protocol-Version`
    */
  final case class Config(
      path: EndpointPath = "/mcp",
      allowOrigin: String => Boolean = Loopback.origin,
      allowHost: String => Boolean = Loopback.host,
      maxBodyBytes: Int = 4 * 1024 * 1024,
      cors: Boolean = false
  )

  object Loopback:
    private val names = Set("localhost", "127.0.0.1", "[::1]", "::1")

    /** `localhost:5000`, `127.0.0.1`, `[::1]:5000`, ... */
    def host(value: String): Boolean =
      val name = if value.startsWith("[") then value.takeWhile(_ != ']') + "]" else value.takeWhile(_ != ':')
      names.contains(name.toLowerCase)

    def origin(value: String): Boolean =
      Uri.fromString(value).toOption.flatMap(_.host).exists(h => names.contains(h.value.toLowerCase))

  private val supported: String = ironmcp.protocol.LatestProtocolVersion

  val ProtocolVersionHeader: CIString = McpHeaders.ProtocolVersion
  val MethodHeader: CIString          = McpHeaders.Method
  val NameHeader: CIString            = McpHeaders.Name

  /** Routes for a server that is the same for every request. */
  def routes[F[_]: Concurrent](server: ServerHandler[F], config: Config): HttpRoutes[F] =
    routesFor(config)(_ => Concurrent[F].pure(Right(server)))

  def routes[F[_]: Concurrent](server: ServerHandler[F]): HttpRoutes[F] = routes(server, Config())

  def routesFor[F[_]](config: Config)(serverFor: Request[F] => F[Either[Response[F], ServerHandler[F]]])(using
      F: Concurrent[F]
  ): HttpRoutes[F] =
    Kleisli { (request: Request[F]) =>
      if request.uri.path.renderString != (config.path: String) then OptionT.none
      else
        val origin = Cors.origin(request, config)
        OptionT.liftF {
          request.method match
            case org.http4s.Method.POST => post(request, config, serverFor).map(Cors.reply(origin))
            case org.http4s.Method.OPTIONS if config.cors => F.pure(Cors.preflight(request, origin))
            case _ =>
              F.pure(Response[F](Status.MethodNotAllowed).putHeaders(Header.Raw(CIString("Allow"), "POST")))
        }
    }

  /** CORS for browser hosts, when [[Config.cors]] is on. Only an origin that
    * `allowOrigin` accepts is ever echoed back, so this opens nothing the
    * Origin check would refuse.
    */
  private object Cors:
    private val exposed = "WWW-Authenticate, MCP-Protocol-Version"

    /** The request's `Origin`, if CORS is on and the origin is allowed. */
    def origin[F[_]](request: Request[F], config: Config): Option[String] =
      if !config.cors then None
      else request.headers.get(CIString("Origin")).map(_.head.value).filter(config.allowOrigin)

    def reply[F[_]](origin: Option[String])(response: Response[F]): Response[F] =
      origin.fold(response)(o =>
        response.putHeaders(
          Header.Raw(CIString("Access-Control-Allow-Origin"), o),
          Header.Raw(CIString("Access-Control-Expose-Headers"), exposed),
          Header.Raw(CIString("Vary"), "Origin")
        )
      )

    /** `204` with what the browser asked to send, for an allowed origin;
      * `403` otherwise.
      */
    def preflight[F[_]](request: Request[F], origin: Option[String]): Response[F] =
      origin match
        case None => Response[F](Status.Forbidden)
        case Some(o) =>
          val asked = request.headers.get(CIString("Access-Control-Request-Headers")).map(_.head.value)
          val allowed = Response[F](Status.NoContent).putHeaders(
            Header.Raw(CIString("Access-Control-Allow-Origin"), o),
            Header.Raw(CIString("Access-Control-Allow-Methods"), "POST, OPTIONS"),
            Header.Raw(CIString("Access-Control-Max-Age"), "600"),
            Header.Raw(CIString("Vary"), "Origin")
          )
          asked.fold(allowed)(h => allowed.putHeaders(Header.Raw(CIString("Access-Control-Allow-Headers"), h)))

  /** Binds the routes to `host:port`. The default host is loopback, as the spec
    * advises for a local server.
    */
  def serve[F[_]: {Async, Network}](
      routes: HttpRoutes[F],
      port: Port,
      host: String = "127.0.0.1"
  ): Resource[F, Server] =
    EmberServerBuilder
      .default[F]
      .withHost(Host.fromString(host).getOrElse(Host.fromString("127.0.0.1").get))
      .withPort(IpPort.fromInt(port).get)
      .withHttpApp(routes.orNotFound)
      .build

  private def post[F[_]](
      request: Request[F],
      config: Config,
      serverFor: Request[F] => F[Either[Response[F], ServerHandler[F]]]
  )(using F: Concurrent[F]): F[Response[F]] =
    val origin = request.headers.get(CIString("Origin")).map(_.head.value)
    val host   = request.headers.get(CIString("Host")).map(_.head.value)
    if origin.exists(o => !config.allowOrigin(o)) then F.pure(failure(Status.Forbidden, None, ErrorCode.InvalidRequest, "origin not allowed"))
    else if host.exists(h => !config.allowHost(h)) then F.pure(failure(Status.Forbidden, None, ErrorCode.InvalidRequest, "host not allowed"))
    else
      request.body.take(config.maxBodyBytes.toLong + 1).compile.to(Array).flatMap { bytes =>
        if bytes.length > config.maxBodyBytes then
          F.pure(failure(Status.PayloadTooLarge, None, ErrorCode.InvalidRequest, "request body too large"))
        else
          Wire.decode(String(bytes, UTF_8)) match
            case Left(error) => F.pure(failure(Status.BadRequest, None, error))
            case Right(JsonRpcMessage.Notification(_, _)) => F.pure(Response[F](Status.Accepted))
            case Right(JsonRpcMessage.Success(_, _) | JsonRpcMessage.Failure(_, _)) =>
              F.pure(failure(Status.BadRequest, None, ErrorCode.InvalidRequest, "a client does not send responses"))
            case Right(message @ JsonRpcMessage.Request(id, method, params)) =>
              headerProblem(request, method, params) match
                case Some(error) => F.pure(failure(Status.BadRequest, Some(id), error))
                case None =>
                  serverFor(request).flatMap {
                    case Left(response) => F.pure(response)
                    case Right(server) => answer(server, message, request)
                  }
      }

  /** Answers one validated request with `server`: plain JSON, or an SSE stream
    * when the request emits notifications and `request` accepts one. For
    * transports that do their own checks first and need the same answer.
    */
  def answer[F[_]: Concurrent](server: ServerHandler[F], message: JsonRpcMessage.Request, request: Request[F]): F[Response[F]] =
    if acceptsEventStream(request) then streamed(server, message)
    else server.handleRecovering(message).map(replied)

  private def replied[F[_]](reply: Option[JsonRpcMessage]): Response[F] = reply match
    case Some(message) => json(statusOf(message), Wire.encode(message))
    case None          => Response[F](Status.Accepted)

  private def acceptsEventStream[F[_]](request: Request[F]): Boolean =
    request.headers.get[headers.Accept].exists(_.values.exists(_.mediaRange.satisfiedBy(MediaType.`text/event-stream`)))

  /** Runs the request with a sink, and answers with SSE only once a
    * notification arrives ahead of the reply. One queue carries both, in order,
    * so the first item decides: a reply means nothing was emitted before it.
    */
  private def streamed[F[_]](server: ServerHandler[F], message: JsonRpcMessage)(using F: Concurrent[F]): F[Response[F]] =
    for
      queue <- Queue.unbounded[F, Either[JsonRpcMessage.Notification, Option[JsonRpcMessage]]]
      fiber <- server.handleEmittingRecovering(message, n => queue.offer(Left(n))).flatMap(reply => queue.offer(Right(reply))).start
      first <- queue.take
    yield first match
      case Right(reply) => replied(reply)
      case Left(notification) =>
        val rest = Stream.fromQueueUnterminated(queue).takeThrough(_.isLeft)
        val events = (Stream.emit(Left(notification)) ++ rest).collect {
          case Left(n)            => ServerSentEvent(data = Some(Wire.encode(n)))
          case Right(Some(reply)) => ServerSentEvent(data = Some(Wire.encode(reply)))
        }
        // A client that hangs up stops the request with it.
        Response[F](Status.Ok).withEntity(events.onFinalize(fiber.cancel))

  /** The header checks of "Server Validation", in the order the spec lists them. */
  private def headerProblem[F[_]](request: Request[F], method: String, params: Option[Json]): Option[JsonRpcError] =
    def header(name: CIString) = request.headers.get(name).map(_.head.value)
    val bodyVersion            = params.flatMap(_.hcursor.downField("_meta").get[String](ReservedMeta.ProtocolVersion).toOption)
    val bodyName = method match
      case "tools/call" | "prompts/get" => params.flatMap(_.hcursor.get[String]("name").toOption).map(Some(_))
      case "resources/read"             => params.flatMap(_.hcursor.get[String]("uri").toOption).map(Some(_))
      case _                            => Some(None)

    header(ProtocolVersionHeader) match
      case None => Some(mismatch("the MCP-Protocol-Version header is required"))
      case Some(version) if version != supported =>
        Some(
          JsonRpcError(
            ErrorCode.UnsupportedProtocolVersion,
            "unsupported protocol version",
            Some(Json.obj("supported" -> Json.arr(Json.fromString(supported)), "requested" -> Json.fromString(version)))
          )
        )
      case Some(version) if bodyVersion.exists(_ != version) =>
        Some(mismatch(s"MCP-Protocol-Version header '$version' does not match the body's '${bodyVersion.get}'"))
      case Some(_) =>
        header(MethodHeader) match
          case None                         => Some(mismatch("the Mcp-Method header is required"))
          case Some(value) if value != method => Some(mismatch(s"Mcp-Method header '$value' does not match body method '$method'"))
          case Some(_) =>
            bodyName match
              case Some(None) => None
              // The body has no name/uri to mirror; the server will report the missing param.
              case None => None
              case Some(Some(expected)) =>
                header(NameHeader).map(McpHeaders.decode) match
                  case None => Some(mismatch("the Mcp-Name header is required"))
                  case Some(None) => Some(mismatch("the Mcp-Name header is not valid base64"))
                  case Some(Some(value)) if value != expected =>
                    Some(mismatch(s"Mcp-Name header value '$value' does not match body value '$expected'"))
                  case Some(Some(_)) => None

  private def mismatch(message: String): JsonRpcError =
    JsonRpcError(ErrorCode.HeaderMismatch, message.asInstanceOf[NonEmptyString])

  /** Errors the spec gives an HTTP status of their own; everything else is a 200. */
  private def statusOf(reply: JsonRpcMessage): Status = reply match
    case JsonRpcMessage.Failure(_, error) =>
      error.code match
        case ErrorCode.MethodNotFound => Status.NotFound
        case ErrorCode.UnsupportedProtocolVersion | ErrorCode.HeaderMismatch | ErrorCode.MissingRequiredClientCapability =>
          Status.BadRequest
        case _ => Status.Ok
    case _ => Status.Ok

  private def failure[F[_]](status: Status, id: Option[RequestId], code: ErrorCode, message: String): Response[F] =
    failure(status, id, JsonRpcError(code, message.asInstanceOf[NonEmptyString]))

  private def failure[F[_]](status: Status, id: Option[RequestId], error: JsonRpcError): Response[F] =
    json(status, Wire.encode(JsonRpcMessage.Failure(id, error)))

  private def json[F[_]](status: Status, body: String): Response[F] =
    Response[F](status)
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
