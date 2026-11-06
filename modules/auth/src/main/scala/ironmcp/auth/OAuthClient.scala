package ironmcp
package auth

import cats.effect.{Async, Ref}
import cats.effect.std.SecureRandom
import cats.syntax.all.*
import io.circe.Codec
import ironmcp.client.McpClientError
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

import java.net.URI
import java.nio.charset.StandardCharsets.US_ASCII
import scala.collection.immutable.ListMap
import java.security.MessageDigest
import java.util.Base64

/** RFC 8414 Authorization Server Metadata: the fields an MCP client uses. */
final case class AuthorizationServerMetadata(
    issuer: String,
    authorization_endpoint: Option[String] = None,
    token_endpoint: String,
    jwks_uri: Option[String] = None,
    registration_endpoint: Option[String] = None,
    introspection_endpoint: Option[String] = None,
    revocation_endpoint: Option[String] = None,
    scopes_supported: Option[List[String]] = None,
    grant_types_supported: Option[List[String]] = None,
    code_challenge_methods_supported: Option[List[String]] = None,
    token_endpoint_auth_methods_supported: Option[List[String]] = None,
    token_endpoint_auth_signing_alg_values_supported: Option[List[String]] = None,
    dpop_signing_alg_values_supported: Option[List[String]] = None,
    authorization_response_iss_parameter_supported: Option[Boolean] = None,
    client_id_metadata_document_supported: Option[Boolean] = None,
    authorization_grant_profiles_supported: Option[List[String]] = None
) derives Codec.AsObject

/** What discovery found: the protected resource and its authorization server. */
final case class Discovered(resource: ProtectedResourceMetadata, authorizationServer: AuthorizationServerMetadata)

/** Why the client refused to go on. Each is a check the MCP authorization
  * spec, or plain prudence, asks a client to make before it sends anything to
  * an authorization server.
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
  case MixUp(expected: String, got: Option[String])
    extends OAuthError(s"the authorization response came from '${got.getOrElse("(no iss)")}', not '$expected' (RFC 9207 mix-up defence)")
  case TokenRequestFailed(status: Int, body: String)
    extends OAuthError(s"token request failed with HTTP $status: $body")
  case TokenEndpoint(status: Int, error: String, description: Option[String])
    extends OAuthError(s"the authorization server refused (HTTP $status): $error${description.fold("")(d => s" ($d)")}")
  case UnexpectedTokenType(tokenType: String)
    extends OAuthError(s"the authorization server issued a '$tokenType' token, not Bearer or DPoP")
  case ClientAuthUnsupported(method: String, supported: List[String])
    extends OAuthError(s"the authorization server does not accept '$method' client authentication (it lists ${supported.mkString(", ")})")
  case GrantUnsupported(issuer: String, grant: String)
    extends OAuthError(s"'$issuer' does not advertise '$grant'")
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

/** What the redirect brought back: `code`, `state`, and the issuer that sent
  * it (`iss`, RFC 9207), when the server includes it.
  */
final case class AuthorizationResponse(code: String, state: String, iss: Option[String] = None)

object AuthorizationResponse:
  /** From the redirect URI's query. */
  def fromRedirect(uri: Uri): Option[AuthorizationResponse] =
    (uri.query.params.get("code"), uri.query.params.get("state")).mapN((c, s) => AuthorizationResponse(c, s, uri.query.params.get("iss")))

/** The client side of MCP authorization: discovery from a `401`, the
  * authorization code flow with PKCE, and refresh.
  *
  *   1. [[discover]] follows the challenge's `resource_metadata` to the RFC
  *      9728 document, which must be on the resource's own origin and name
  *      exactly the resource called, then fetches the authorization server's
  *      RFC 8414 metadata (or OpenID configuration), checking its `issuer`.
  *      Every URL on the way must be `https` (loopback excepted).
  *   2. [[authorizationRequest]] builds the URL the user opens, with PKCE
  *      and the RFC 8707 `resource`.
  *   3. [[exchangeCode]] and [[refresh]] call the token endpoint, with the
  *      client's [[ClientAuth]] and, if it has one, a DPoP proof.
  *
  * [[authorizer]] runs all of it as the `Set[String] => F[TokenResponse]` that
  * [[StepUpClient.create]] takes, and checks the redirect's `state` and, per
  * RFC 9207, its `iss` (the defence against a malicious authorization server
  * mixing up the user's sessions).
  *
  * `trust` decides which authorization servers may receive the user and the
  * client's credentials. The resource server names them, so a client that
  * trusts any name can be sent to an attacker's server (the class of bug in
  * GHSA-6qxp-vccf-f47h). Give it an allowlist.
  */
final class OAuthClient[F[_]](http: Client[F], random: SecureRandom[F], trust: String => Boolean, dpop: Option[Dpop.Key])(using
    F: Async[F]
):

  /** Discovery from a rejected MCP request. `resource` is the MCP endpoint
    * that was called; the metadata must name exactly it.
    */
  def discover(rejection: McpClientError.Http, resource: String): F[Discovered] =
    AuthChallenge.parse(rejection.wwwAuthenticate).flatMap(_.param("resource_metadata")).headOption match
      case None      => F.raiseError(OAuthError.NoResourceMetadata("the challenge has no resource_metadata parameter"))
      case Some(url) => discoverFrom(url, resource)

  /** Discovery from a known RFC 9728 metadata URL. */
  def discoverFrom(resourceMetadataUrl: String, resource: String): F[Discovered] =
    for
      _ <- secure(resourceMetadataUrl)
      _ <- F.raiseWhen(origin(resourceMetadataUrl) != origin(resource))(
        OAuthError.NoResourceMetadata(s"'$resourceMetadataUrl' is not on the origin of '$resource'")
      )
      uri      <- F.fromEither(Uri.fromString(resourceMetadataUrl)).adaptError(e => OAuthError.NoResourceMetadata(e.getMessage))
      metadata <- http.expect[ProtectedResourceMetadata](uri).adaptError { case e => OAuthError.NoResourceMetadata(e.getMessage) }
      _ <- F.raiseWhen(normalize(metadata.resource) != normalize(resource))(OAuthError.ResourceMismatch(resource, metadata.resource))
      issuer <- metadata.authorization_servers.find(trust) match
        case Some(found) => F.pure(found)
        case None        => F.raiseError(OAuthError.UntrustedAuthorizationServer(metadata.authorization_servers.mkString(", ")))
      server <- authorizationServer(issuer)
    yield Discovered(metadata, server)

  /** RFC 8414 metadata for `issuer`, trying the well-known locations in the
    * order the MCP authorization spec gives, and checking the document names
    * the issuer it was fetched for and that its endpoints are secure.
    */
  def authorizationServer(issuer: String): F[AuthorizationServerMetadata] =
    secure(issuer) *> (Uri.fromString(issuer) match
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
          F.raiseWhen(normalize(found.issuer) != normalize(issuer))(OAuthError.IssuerMismatch(issuer, found.issuer)) *>
            (found.token_endpoint :: found.authorization_endpoint.toList).traverse_(secure)
        }
    )

  /** The `client_id` to use with this server: the Client ID Metadata
    * Document URL when the server accepts those, else the pre-registered ID.
    */
  def clientId(discovered: Discovered, registered: Option[String], metadataDocument: Option[String]): Option[String] =
    metadataDocument.filter(_ => discovered.authorizationServer.client_id_metadata_document_supported.contains(true)).orElse(registered)

  /** The URL to send the user to, with the PKCE pair and `state` that the
    * redirect must be checked against.
    */
  def authorizationRequest(discovered: Discovered, client: ClientAuth, redirectUri: String, scopes: Set[String]): F[(Uri, Pkce.Pair, String)] =
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
            "client_id"             -> client.clientId,
            "redirect_uri"          -> redirectUri,
            "code_challenge"        -> pkce.challenge,
            "code_challenge_method" -> "S256",
            "state"                 -> state,
            "resource"              -> discovered.resource.resource
          ) ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" "))
            ++ dpop.map(k => "dpop_jkt" -> k.thumbprint)
        )
        (uri, pkce, state)

  /** Checks a redirect against the request it answers: `state`, then the
    * RFC 9207 `iss`, required whenever the server says it sends it.
    */
  def checkResponse(discovered: Discovered, response: AuthorizationResponse, state: String): Either[OAuthError, Unit] =
    val server = discovered.authorizationServer
    for
      _ <- Either.cond(MessageDigest.isEqual(response.state.getBytes(US_ASCII), state.getBytes(US_ASCII)), (), OAuthError.StateMismatch)
      required = server.authorization_response_iss_parameter_supported.contains(true)
      _ <- (response.iss, required) match
        case (Some(iss), _)  => Either.cond(normalize(iss) == normalize(server.issuer), (), OAuthError.MixUp(server.issuer, Some(iss)))
        case (None, true)    => Left(OAuthError.MixUp(server.issuer, None))
        case (None, false)   => Right(())
    yield ()

  /** Trades an authorization code for tokens, proving possession of the verifier. */
  def exchangeCode(discovered: Discovered, client: ClientAuth, redirectUri: String, code: String, verifier: String): F[TokenResponse] =
    token(discovered, client, List("grant_type" -> "authorization_code", "code" -> code, "redirect_uri" -> redirectUri, "code_verifier" -> verifier))

  /** A new access token from a refresh token. A refresh can narrow the scopes
    * but never widen them: a step-up needs a new authorization.
    */
  def refresh(discovered: Discovered, client: ClientAuth, refreshToken: String, scopes: Set[String] = Set.empty): F[TokenResponse] =
    token(
      discovered,
      client,
      List("grant_type" -> "refresh_token", "refresh_token" -> refreshToken) ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" "))
    )

  /** The whole flow as [[StepUpClient]]'s `authorize`: it refreshes when the
    * requested scopes are ones already granted and a refresh token is held,
    * and otherwise sends the user to authorize through `userAgent`, which
    * opens the URL and returns what the redirect brought back.
    */
  def authorizer(discovered: Discovered, client: ClientAuth, redirectUri: String, userAgent: Uri => F[AuthorizationResponse]): F[Set[String] => F[TokenResponse]] =
    Ref.of[F, Option[TokenResponse]](None).map { held => (scopes: Set[String]) =>
      def authorize: F[TokenResponse] =
        for
          (uri, pkce, state) <- authorizationRequest(discovered, client, redirectUri, scopes)
          response           <- userAgent(uri)
          _                  <- checkResponse(discovered, response, state).liftTo[F]
          tokens             <- exchangeCode(discovered, client, redirectUri, response.code, pkce.verifier)
        yield tokens

      held.get
        .flatMap {
          case Some(previous) if scopes.subsetOf(previous.grantedScopes) && previous.refresh_token.isDefined =>
            refresh(discovered, client, previous.refresh_token.get, scopes)
              // A rotated refresh token replaces the old one; an expired or revoked one sends the user back.
              .map(fresh => fresh.copy(refresh_token = fresh.refresh_token.orElse(previous.refresh_token)))
              .handleErrorWith(_ => authorize)
          case _ => authorize
        }
        .flatTap(tokens => held.set(Some(tokens)))
    }

  private def token(discovered: Discovered, client: ClientAuth, params: List[(String, String)]): F[TokenResponse] =
    val server = discovered.authorizationServer
    for
      _ <- ClientAuth.check(client, server.token_endpoint_auth_methods_supported).liftTo[F]
      tokens <- OAuthEndpoint.post[F, TokenResponse](
        http,
        server.issuer,
        server.token_endpoint,
        client,
        params :+ ("resource" -> discovered.resource.resource),
        dpop = dpop
      )
      _ <- OAuthClient.checkTokenType(tokens).liftTo[F]
    yield tokens

  private def secure(url: String): F[Unit] = ResourceIdentity.requireSecure(url).leftMap(OAuthError.Insecure(_)).liftTo[F]

  private def origin(url: String): Option[String] =
    scala.util.Try(new URI(url)).toOption.map(u => s"${u.getScheme.toLowerCase}://${u.getHost.toLowerCase}:${u.getPort}")

  private def normalize(url: String): String = url.stripSuffix("/")

object OAuthClient:

  /** A client that trusts only the listed authorization servers. With a DPoP
    * key, its tokens are bound to that key (send them through
    * [[Dpop.client]]).
    */
  def apply[F[_]: Async](http: Client[F], random: SecureRandom[F], trusted: Set[String], dpop: Option[Dpop.Key] = None): OAuthClient[F] =
    new OAuthClient(http, random, issuer => trusted.contains(issuer.stripSuffix("/")) || trusted.contains(issuer), dpop)

  /** Bearer or DPoP (RFC 6749 §7.1 token types are case-insensitive). */
  def checkTokenType(tokens: TokenResponse): Either[OAuthError, Unit] =
    Either.cond(
      tokens.token_type.equalsIgnoreCase("Bearer") || tokens.token_type.equalsIgnoreCase("DPoP"),
      (),
      OAuthError.UnexpectedTokenType(tokens.token_type)
    )
