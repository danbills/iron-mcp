package ironmcp
package auth

import cats.effect.{Concurrent, Ref}
import cats.effect.std.SecureRandom
import cats.syntax.all.*
import io.circe.Codec
import ironmcp.client.McpClientError
import ironmcp.http.McpHttpClient
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`

import java.nio.charset.StandardCharsets.US_ASCII
import scala.collection.immutable.ListMap
import java.security.MessageDigest
import java.util.Base64

/** RFC 8414 Authorization Server Metadata (the fields an MCP client uses). */
final case class AuthorizationServerMetadata(
    issuer: String,
    authorization_endpoint: Option[String] = None,
    token_endpoint: String,
    registration_endpoint: Option[String] = None,
    scopes_supported: Option[List[String]] = None,
    code_challenge_methods_supported: Option[List[String]] = None
) derives Codec.AsObject

/** What discovery found: the protected resource and its authorization server. */
final case class Discovered(resource: ProtectedResourceMetadata, authorizationServer: AuthorizationServerMetadata)

/** Why the client refused to go on. Each is a check the MCP authorization
  * spec asks a client to make before it sends anything to an authorization
  * server.
  */
enum OAuthError(message: String) extends Exception(message):
  case NoResourceMetadata(detail: String)
    extends OAuthError(s"no protected resource metadata: $detail")
  case ResourceMismatch(expected: String, advertised: String)
    extends OAuthError(s"the resource metadata is for '$advertised', not '$expected' (RFC 9728 §3.3)")
  case UntrustedAuthorizationServer(issuer: String)
    extends OAuthError(s"the resource names authorization server '$issuer', which this client does not trust")
  case NoAuthorizationServerMetadata(issuer: String)
    extends OAuthError(s"no RFC 8414 or OpenID metadata found for '$issuer'")
  case IssuerMismatch(expected: String, advertised: String)
    extends OAuthError(s"metadata fetched for '$expected' names issuer '$advertised' (RFC 8414 §3.3)")
  case PkceUnsupported(issuer: String)
    extends OAuthError(s"'$issuer' does not advertise PKCE S256; an MCP client must refuse it")
  case StateMismatch
    extends OAuthError("the authorization response's state does not match the request's (possible CSRF)")
  case TokenRequestFailed(status: Int, body: String)
    extends OAuthError(s"token request failed with HTTP $status: $body")
  case TokenEndpoint(status: Int, error: String, description: Option[String])
    extends OAuthError(s"the authorization server refused (HTTP $status): $error${description.fold("")(d => s" ($d)")}")
  case ClientAuthUnsupported(method: String, supported: List[String])
    extends OAuthError(s"the authorization server does not accept '$method' client authentication (it lists ${supported.mkString(", ")})")
  case Insecure(reason: String)
    extends OAuthError(s"refusing an insecure endpoint: $reason")

/** RFC 7636 Proof Key for Code Exchange, S256 only (OAuth 2.1 drops `plain`). */
object Pkce:

  final case class Pair(verifier: String, challenge: String)

  private val base64Url = Base64.getUrlEncoder.withoutPadding

  /** `BASE64URL(SHA256(ASCII(verifier)))`. */
  def challengeFor(verifier: String): String =
    base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII)))

  /** A fresh verifier: 32 random bytes, 43 characters once encoded. */
  def generate[F[_]: cats.Functor](random: SecureRandom[F]): F[Pair] =
    random.nextBytes(32).map { bytes =>
      val verifier = base64Url.encodeToString(bytes)
      Pair(verifier, challengeFor(verifier))
    }

/** What the redirect brought back: the `code` and `state` query parameters. */
final case class AuthorizationResponse(code: String, state: String)

/** The client side of MCP authorization: discovery from a `401`, the
  * authorization code flow with PKCE, and refresh.
  *
  *   1. [[discover]] follows the challenge's `resource_metadata` to the RFC
  *      9728 document, checks it describes the resource that was called, and
  *      fetches the authorization server's RFC 8414 metadata (or OpenID
  *      configuration), checking its `issuer`.
  *   2. [[authorizationRequest]] builds the URL the user opens, with PKCE and
  *      the RFC 8707 `resource` parameter.
  *   3. [[exchangeCode]] and [[refresh]] call the token endpoint.
  *
  * [[authorizer]] runs all of it as the `Set[String] => F[TokenResponse]` that
  * [[StepUpClient.create]] takes.
  *
  * `trust` decides which authorization servers may receive the user and the
  * client's credentials. The resource server names them, so a client that
  * trusts any name can be sent to an attacker's server (the class of bug in
  * GHSA-6qxp-vccf-f47h). Give it an allowlist.
  */
final class OAuthClient[F[_]](http: Client[F], random: SecureRandom[F], trust: String => Boolean)(using F: Concurrent[F]):

  /** Discovery from a rejected MCP request. `resource` is the MCP endpoint
    * that was called; the metadata must name exactly it.
    */
  def discover(rejection: McpClientError.Http, resource: String): F[Discovered] =
    McpHttpClient
      .challenges(rejection)
      .flatMap(_.params.get("resource_metadata"))
      .headOption match
      case None      => F.raiseError(OAuthError.NoResourceMetadata("the challenge has no resource_metadata parameter"))
      case Some(url) => discoverFrom(url, resource)

  /** Discovery from a known RFC 9728 metadata URL. */
  def discoverFrom(resourceMetadataUrl: String, resource: String): F[Discovered] =
    for
      uri      <- F.fromEither(Uri.fromString(resourceMetadataUrl)).adaptError(e => OAuthError.NoResourceMetadata(e.getMessage))
      metadata <- http.expect[ProtectedResourceMetadata](uri).adaptError { case e => OAuthError.NoResourceMetadata(e.getMessage) }
      _ <- F.raiseWhen(normalize(metadata.resource) != normalize(resource))(OAuthError.ResourceMismatch(resource, metadata.resource))
      issuer <- metadata.authorization_servers.find(trust) match
                  case Some(found) => F.pure(found)
                  case None =>
                    F.raiseError(OAuthError.UntrustedAuthorizationServer(metadata.authorization_servers.mkString(", ")))
      server <- authorizationServer(issuer)
    yield Discovered(metadata, server)

  /** RFC 8414 metadata for `issuer`, trying the well-known locations in the
    * order the MCP authorization spec gives, and checking the document names
    * the issuer it was fetched for.
    */
  def authorizationServer(issuer: String): F[AuthorizationServerMetadata] =
    Uri.fromString(issuer) match
      case Left(_) => F.raiseError(OAuthError.NoAuthorizationServerMetadata(issuer))
      case Right(base) =>
        val path   = base.path.renderString.stripSuffix("/")
        val origin = base.withPath(Uri.Path.empty).renderString.stripSuffix("/")
        val candidates =
          if path.isEmpty then List(s"$origin/.well-known/oauth-authorization-server", s"$origin/.well-known/openid-configuration")
          else
            List(
              s"$origin/.well-known/oauth-authorization-server$path",
              s"$origin/.well-known/openid-configuration$path",
              s"$origin$path/.well-known/openid-configuration"
            )
        def attempt(remaining: List[String]): F[AuthorizationServerMetadata] = remaining match
          case Nil => F.raiseError(OAuthError.NoAuthorizationServerMetadata(issuer))
          case next :: rest =>
            http.expectOption[AuthorizationServerMetadata](Request[F](Method.GET, Uri.unsafeFromString(next))).attempt.flatMap {
              case Right(Some(found)) => F.pure(found)
              case _                  => attempt(rest)
            }
        attempt(candidates).flatTap { found =>
          F.raiseWhen(normalize(found.issuer) != normalize(issuer))(OAuthError.IssuerMismatch(issuer, found.issuer))
        }

  /** The URL to send the user to, with the PKCE pair and `state` that the
    * redirect must be checked against.
    */
  def authorizationRequest(
      discovered: Discovered,
      clientId: String,
      redirectUri: String,
      scopes: Set[String]
  ): F[(Uri, Pkce.Pair, String)] =
    val server = discovered.authorizationServer
    if !server.code_challenge_methods_supported.exists(_.contains("S256")) then F.raiseError(OAuthError.PkceUnsupported(server.issuer))
    else
      for
        endpoint <- F.fromEither(Uri.fromString(server.authorization_endpoint.getOrElse(s"${server.issuer.stripSuffix("/")}/authorize")))
        pkce     <- Pkce.generate(random)
        state    <- random.nextBytes(16).map(Base64.getUrlEncoder.withoutPadding.encodeToString)
      yield
        val uri = endpoint.withQueryParams(
          ListMap(
            "response_type"         -> "code",
            "client_id"             -> clientId,
            "redirect_uri"          -> redirectUri,
            "code_challenge"        -> pkce.challenge,
            "code_challenge_method" -> "S256",
            "state"                 -> state,
            "resource"              -> discovered.resource.resource
          ) ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" "))
        )
        (uri, pkce, state)

  /** Trades an authorization code for tokens, proving possession of the verifier. */
  def exchangeCode(discovered: Discovered, clientId: String, redirectUri: String, code: String, verifier: String): F[TokenResponse] =
    token(
      discovered,
      List(
        "grant_type"    -> "authorization_code",
        "code"          -> code,
        "redirect_uri"  -> redirectUri,
        "client_id"     -> clientId,
        "code_verifier" -> verifier
      )
    )

  /** A new access token from a refresh token. A refresh can narrow the scopes
    * but never widen them: a step-up needs a new authorization.
    */
  def refresh(discovered: Discovered, clientId: String, refreshToken: String, scopes: Set[String] = Set.empty): F[TokenResponse] =
    token(
      discovered,
      List("grant_type" -> "refresh_token", "refresh_token" -> refreshToken, "client_id" -> clientId)
        ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" "))
    )

  /** The whole flow as [[StepUpClient]]'s `authorize`: it refreshes when the
    * requested scopes are ones already granted and a refresh token is held,
    * and otherwise sends the user to authorize through `userAgent`, which
    * opens the URL and returns what the redirect brought back.
    */
  def authorizer(
      discovered: Discovered,
      clientId: String,
      redirectUri: String,
      userAgent: Uri => F[AuthorizationResponse]
  ): F[Set[String] => F[TokenResponse]] =
    Ref.of[F, Option[TokenResponse]](None).map { held => (scopes: Set[String]) =>
      def authorize: F[TokenResponse] =
        for
          (uri, pkce, state) <- authorizationRequest(discovered, clientId, redirectUri, scopes)
          response           <- userAgent(uri)
          _                  <- F.raiseWhen(response.state != state)(OAuthError.StateMismatch)
          tokens             <- exchangeCode(discovered, clientId, redirectUri, response.code, pkce.verifier)
        yield tokens

      held.get
        .flatMap {
          case Some(previous) if scopes.subsetOf(previous.grantedScopes) && previous.refresh_token.isDefined =>
            refresh(discovered, clientId, previous.refresh_token.get, scopes)
              // A refresh token can expire or be revoked: fall back to the user.
              .map(fresh => fresh.copy(refresh_token = fresh.refresh_token.orElse(previous.refresh_token)))
              .handleErrorWith(_ => authorize)
          case _ => authorize
        }
        .flatTap(tokens => held.set(Some(tokens)))
    }

  private def token(discovered: Discovered, params: List[(String, String)]): F[TokenResponse] =
    val request = Request[F](Method.POST, Uri.unsafeFromString(discovered.authorizationServer.token_endpoint))
      .withEntity(UrlForm((params :+ ("resource" -> discovered.resource.resource))*))
      .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))
    http.run(request).use { response =>
      if response.status.isSuccess then response.as[TokenResponse]
      else response.bodyText.compile.string.flatMap(body => F.raiseError(OAuthError.TokenRequestFailed(response.status.code, body)))
    }

  private def normalize(url: String): String = url.stripSuffix("/")

object OAuthClient:

  /** A client that trusts only the listed authorization servers. */
  def apply[F[_]: Concurrent](http: Client[F], random: SecureRandom[F], trusted: Set[String]): OAuthClient[F] =
    new OAuthClient(http, random, issuer => trusted.contains(issuer.stripSuffix("/")) || trusted.contains(issuer))
