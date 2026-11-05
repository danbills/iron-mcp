package ironmcp
package auth

import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import ironmcp.http.{McpHeaders, McpHttp}
import ironmcp.server.ServerHandler
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** Resource Server middleware enforcing OAuth 2.1, RFC 9728 metadata,
  * RFC 8707 audience binding, and per-tool scope enforcement (§9.2 & §9.7.4).
  */
object McpAuthServer:

  final case class Config(
      mcpConfig: McpHttp.Config = McpHttp.Config(),
      serverUrl: String,
      metadata: ProtectedResourceMetadata,
      scopePolicy: ToolScopePolicy = ToolScopePolicy.open
  ):
    /** RFC 9728 path-aware well-known URL. */
    val resourceMetadataUrl: String =
      val p = mcpConfig.path: String
      val suffix = if p.isEmpty || p == "/" then "" else p
      s"${serverUrl.stripSuffix("/")}/.well-known/oauth-protected-resource$suffix"

  private val WwwAuthenticateHeader: CIString = CIString("WWW-Authenticate")
  private val McpMethodHeader: CIString       = McpHeaders.Method
  private val McpNameHeader: CIString         = McpHeaders.Name

  /** Routes for an MCP server where all tools share the same claims. */
  def routes[F[_]: Concurrent](
      server: ServerHandler[F],
      validator: TokenValidator[F],
      config: Config
  ): HttpRoutes[F] =
    routesFor(validator, config)((_, _) => Concurrent[F].pure(Right(server)))

  /** Routes building a server closed over the validated [[TokenClaims]]. */
  def routesFor[F[_]: Concurrent](
      validator: TokenValidator[F],
      config: Config
  )(
      serverFor: (Request[F], TokenClaims) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    val wellKnownRoutes = metadataRoutes(config)
    val protectedMcp = createMcpRoutes(validator, config, serverFor)
    wellKnownRoutes <+> protectedMcp

  /** Routes serving RFC 9728 metadata at root and path-aware endpoints. */
  def metadataRoutes[F[_]: Concurrent](config: Config): HttpRoutes[F] =
    val rootPath = "/.well-known/oauth-protected-resource"
    val mcpPath  = (config.mcpConfig.path: String)
    val specificPath = if mcpPath.isEmpty || mcpPath == "/" then rootPath else s"$rootPath$mcpPath"

    HttpRoutes.of[F] {
      case req if req.method == Method.GET && (req.uri.path.renderString == rootPath || req.uri.path.renderString == specificPath) =>
        Concurrent[F].pure(
          Response[F](Status.Ok)
            .withEntity(config.metadata.asJson)
            .withContentType(`Content-Type`(MediaType.application.json))
        )
    }

  private def createMcpRoutes[F[_]: Concurrent](
      validator: TokenValidator[F],
      config: Config,
      serverFor: (Request[F], TokenClaims) => F[Either[Response[F], ServerHandler[F]]]
  ): HttpRoutes[F] =
    McpHttp.routesFor[F](config.mcpConfig) { (req: Request[F]) =>
      // Extract Bearer token
      val authHeader = req.headers.get(CIString("Authorization")).map(_.head.value)
      val tokenOpt = authHeader.flatMap { h =>
        if h.startsWith("Bearer ") then Some(h.substring("Bearer ".length).trim)
        else None
      }

      tokenOpt match
        case None =>
          Concurrent[F].pure(Left(unauthorizedResponse(AuthError.MissingToken, config.resourceMetadataUrl)))
        case Some(token) =>
          validator.validate(token).flatMap {
            case Left(err) =>
              Concurrent[F].pure(Left(unauthorizedResponse(err, config.resourceMetadataUrl)))
            case Right(claims) =>
              // Check per-tool scopes if this is a tools/call request
              checkToolScope(req, claims, config) match
                case Some(forbidden) => Concurrent[F].pure(Left(forbidden))
                case None            => serverFor(req, claims)
          }
    }

  private def checkToolScope[F[_]](
      req: Request[F],
      claims: TokenClaims,
      config: Config
  ): Option[Response[F]] =
    val isToolsCall = req.headers.get(McpMethodHeader).exists(_.head.value == "tools/call")
    if !isToolsCall then None
    else
      val toolName = req.headers.get(McpNameHeader).map(_.head.value).flatMap(McpHeaders.decode)
      toolName.flatMap { name =>
        config.scopePolicy.check(name, claims.scopes) match
          case Right(()) => None
          case Left(required) =>
            val challenge =
              s"""Bearer error="insufficient_scope", error_description="'$name' requires [${required.mkString(" ")}]; the token grants [${claims.scopes.mkString(" ")}].", scope="${required.mkString(" ")}", resource_metadata="${config.resourceMetadataUrl}""""
            val body = Json.obj(
              "error" -> "insufficient_scope".asJson,
              "error_description" -> s"'$name' requires [${required.mkString(" ")}]; the token grants [${claims.scopes.mkString(" ")}].".asJson
            )
            Some(
              Response[F](Status.Forbidden)
                .putHeaders(Header.Raw(WwwAuthenticateHeader, challenge))
                .withEntity(body)
                .withContentType(`Content-Type`(MediaType.application.json))
            )
      }

  private def unauthorizedResponse[F[_]](err: AuthError, resourceMetadataUrl: String): Response[F] =
    val errorKind = err match
      case AuthError.MissingToken => "invalid_request"
      case _                      => "invalid_token"

    val challenge =
      s"""Bearer error="$errorKind", error_description="${err.description}", resource_metadata="$resourceMetadataUrl""""
    val body = Json.obj(
      "error" -> errorKind.asJson,
      "error_description" -> err.description.asJson
    )
    Response[F](Status.Unauthorized)
      .putHeaders(Header.Raw(WwwAuthenticateHeader, challenge))
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
