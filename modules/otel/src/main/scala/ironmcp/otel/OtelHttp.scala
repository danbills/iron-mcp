package ironmcp
package otel

import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import ironmcp.http.{McpHeaders, McpHttp}
import ironmcp.protocol.*
import ironmcp.server.McpServer
import ironmcp.transport.Wire
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8

object OtelHttp:

  private val supported: String = LatestProtocolVersion

  def routes[F[_]: Async](
      server: McpServer[F],
      telemetry: McpTelemetry[F],
      config: McpHttp.Config = McpHttp.Config()
  ): HttpRoutes[F] =
    routesFor(telemetry, config)(_ => Async[F].pure(Right(server)))

  def routesFor[F[_]](
      telemetry: McpTelemetry[F],
      config: McpHttp.Config = McpHttp.Config()
  )(
      serverFor: Request[F] => F[Either[Response[F], McpServer[F]]]
  )(using F: Async[F]): HttpRoutes[F] =
    Kleisli { (request: Request[F]) =>
      if request.uri.path.renderString != (config.path: String) then OptionT.none
      else
        OptionT.liftF {
          request.method match
            case org.http4s.Method.POST => post(request, config, telemetry, serverFor)
            case _ =>
              F.pure(Response[F](Status.MethodNotAllowed).putHeaders(Header.Raw(CIString("Allow"), "POST")))
        }
    }

  private def post[F[_]](
      request: Request[F],
      config: McpHttp.Config,
      telemetry: McpTelemetry[F],
      serverFor: Request[F] => F[Either[Response[F], McpServer[F]]]
  )(using F: Async[F]): F[Response[F]] =
    val origin = request.headers.get(CIString("Origin")).map(_.head.value)
    val host   = request.headers.get(CIString("Host")).map(_.head.value)
    if origin.exists(o => !config.allowOrigin(o)) then
      F.pure(failure(Status.Forbidden, None, ErrorCode.InvalidRequest, "origin not allowed"))
    else if host.exists(h => !config.allowHost(h)) then
      F.pure(failure(Status.Forbidden, None, ErrorCode.InvalidRequest, "host not allowed"))
    else
      val httpContext = TraceContext.fromHeaders(request.headers)
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
                    case Right(server) =>
                      McpHttp.answer(new OtelServer(server, telemetry).handlerFor("http", httpContext), message, request)
                  }
      }

  private def headerProblem[F[_]](request: Request[F], method: String, params: Option[Json]): Option[JsonRpcError] =
    def header(name: CIString) = request.headers.get(name).map(_.head.value)
    val bodyVersion = params.flatMap(_.hcursor.downField("_meta").get[String](ReservedMeta.ProtocolVersion).toOption)
    val bodyName = method match
      case "tools/call" | "prompts/get" => params.flatMap(_.hcursor.get[String]("name").toOption).map(Some(_))
      case "resources/read"             => params.flatMap(_.hcursor.get[String]("uri").toOption).map(Some(_))
      case _                            => Some(None)

    header(McpHeaders.ProtocolVersion) match
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
        header(McpHeaders.Method) match
          case None                           => Some(mismatch("the Mcp-Method header is required"))
          case Some(value) if value != method => Some(mismatch(s"Mcp-Method header '$value' does not match body method '$method'"))
          case Some(_) =>
            bodyName match
              case Some(None) => None
              case None       => None
              case Some(Some(expected)) =>
                header(McpHeaders.Name).map(McpHeaders.decode) match
                  case None       => Some(mismatch("the Mcp-Name header is required"))
                  case Some(None) => Some(mismatch("the Mcp-Name header is not valid base64"))
                  case Some(Some(value)) if value != expected =>
                    Some(mismatch(s"Mcp-Name header value '$value' does not match body value '$expected'"))
                  case Some(Some(_)) => None

  private def mismatch(message: String): JsonRpcError =
    JsonRpcError(ErrorCode.HeaderMismatch, message.asInstanceOf[NonEmptyString])

  private def failure[F[_]](status: Status, id: Option[RequestId], code: ErrorCode, message: String): Response[F] =
    failure(status, id, JsonRpcError(code, message.asInstanceOf[NonEmptyString]))

  private def failure[F[_]](status: Status, id: Option[RequestId], error: JsonRpcError): Response[F] =
    json(status, Wire.encode(JsonRpcMessage.Failure(id, error)))

  private def json[F[_]](status: Status, body: String): Response[F] =
    Response[F](status)
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
