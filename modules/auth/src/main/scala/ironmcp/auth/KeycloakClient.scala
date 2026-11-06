package ironmcp
package auth

import cats.effect.Async
import cats.syntax.all.*
import io.circe.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

/** A Keycloak realm, seen from a resource server or a machine client.
  *
  * Keycloak does not act on the RFC 8707 `resource` parameter: a token's
  * `aud` comes from the realm's Audience mappers. The `iron-mcp` realm file
  * (`modules/auth/keycloak/`) attaches one to each MCP client scope, so a
  * token carrying `mcp:read` is audience-bound to the MCP server; check it
  * with [[TokenValidator]] as with any issuer.
  */
final class KeycloakClient[F[_]: Async](client: Client[F], realmUri: Uri):

  private val base = realmUri.renderString.stripSuffix("/")

  /** The realm's OpenID configuration: the fields a resource server needs. */
  final case class OidcConfig(
      issuer: String,
      token_endpoint: String,
      jwks_uri: String,
      introspection_endpoint: Option[String] = None,
      revocation_endpoint: Option[String] = None
  ) derives Decoder

  def discover: F[OidcConfig] =
    ResourceIdentity.requireSecure(base).leftMap(OAuthError.Insecure(_)).liftTo[F] *>
      client.expect[OidcConfig](Uri.unsafeFromString(s"$base/.well-known/openid-configuration")).flatTap { found =>
        Async[F].raiseWhen(found.issuer.stripSuffix("/") != base)(OAuthError.IssuerMismatch(base, found.issuer))
      }

  /** A token for the client itself (`client_credentials`), for `scopes`. */
  def clientCredentials(oidc: OidcConfig, auth: ClientAuth, scopes: Set[String], resource: Option[String] = None): F[TokenResponse] =
    OAuthEndpoint.post[F, TokenResponse](
      client,
      oidc.issuer,
      oidc.token_endpoint,
      auth,
      List("grant_type" -> "client_credentials")
        ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" "))
        ++ resource.map("resource" -> _)
    )

  /** A new access token from a refresh token. */
  def refreshToken(oidc: OidcConfig, auth: ClientAuth, refreshToken: String, scopes: Set[String] = Set.empty): F[TokenResponse] =
    OAuthEndpoint.post[F, TokenResponse](
      client,
      oidc.issuer,
      oidc.token_endpoint,
      auth,
      List("grant_type" -> "refresh_token", "refresh_token" -> refreshToken)
        ++ Option.when(scopes.nonEmpty)("scope" -> scopes.toList.sorted.mkString(" "))
    )
