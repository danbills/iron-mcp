package ironmcp
package auth

import cats.effect.Concurrent
import io.circe.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`

/** Client helper for Keycloak OIDC operations and token issuance. */
final class KeycloakClient[F[_]: Concurrent](
    client: Client[F],
    realmUri: Uri
):

  private val openidConfigUri: Uri =
    val base = realmUri.renderString.stripSuffix("/")
    Uri.unsafeFromString(s"$base/.well-known/openid-configuration")

  final case class OidcConfig(
      issuer: String,
      token_endpoint: String,
      jwks_uri: String
  )

  object OidcConfig:
    given Decoder[OidcConfig] = Decoder.instance { c =>
      for
        iss <- c.get[String]("issuer")
        tok <- c.get[String]("token_endpoint")
        jwk <- c.get[String]("jwks_uri")
      yield OidcConfig(iss, tok, jwk)
    }

  /** Fetches Keycloak's OpenID Connect discovery document. */
  def discover: F[OidcConfig] =
    client.expect[OidcConfig](openidConfigUri)

  /** Requests an access token using the Client Credentials grant (`client_credentials`). */
  def clientCredentials(
      tokenEndpoint: Uri,
      clientId: String,
      clientSecret: String,
      scopes: Set[String] = Set.empty,
      resource: Option[String] = None
  ): F[TokenResponse] =
    val params = collection.mutable.Map[String, String](
      "grant_type" -> "client_credentials",
      "client_id" -> clientId,
      "client_secret" -> clientSecret
    )
    if scopes.nonEmpty then params("scope") = scopes.mkString(" ")
    resource.foreach(r => params("resource") = r)

    val form = UrlForm(params.toMap.toSeq*)
    val req = Request[F](Method.POST, tokenEndpoint)
      .withEntity(form)
      .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))

    client.expect[TokenResponse](req)

  /** Requests an access token using Direct Access Grants (`password`). */
  def password(
      tokenEndpoint: Uri,
      clientId: String,
      clientSecret: Option[String],
      username: String,
      passwordVal: String,
      scopes: Set[String] = Set.empty,
      resource: Option[String] = None
  ): F[TokenResponse] =
    val params = collection.mutable.Map[String, String](
      "grant_type" -> "password",
      "client_id" -> clientId,
      "username" -> username,
      "password" -> passwordVal
    )
    clientSecret.foreach(s => params("client_secret") = s)
    if scopes.nonEmpty then params("scope") = scopes.mkString(" ")
    resource.foreach(r => params("resource") = r)

    val form = UrlForm(params.toMap.toSeq*)
    val req = Request[F](Method.POST, tokenEndpoint)
      .withEntity(form)
      .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))

    client.expect[TokenResponse](req)

  /** Refreshes an access token using the Refresh Token grant (`refresh_token`). */
  def refreshToken(
      tokenEndpoint: Uri,
      clientId: String,
      clientSecret: Option[String],
      refreshTokenVal: String,
      scopes: Set[String] = Set.empty,
      resource: Option[String] = None
  ): F[TokenResponse] =
    val params = collection.mutable.Map[String, String](
      "grant_type" -> "refresh_token",
      "client_id" -> clientId,
      "refresh_token" -> refreshTokenVal
    )
    clientSecret.foreach(s => params("client_secret") = s)
    if scopes.nonEmpty then params("scope") = scopes.mkString(" ")
    resource.foreach(r => params("resource") = r)

    val form = UrlForm(params.toMap.toSeq*)
    val req = Request[F](Method.POST, tokenEndpoint)
      .withEntity(form)
      .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))

    client.expect[TokenResponse](req)
