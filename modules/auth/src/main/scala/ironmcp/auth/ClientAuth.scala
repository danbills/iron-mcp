package ironmcp
package auth

import cats.effect.{Async, Sync}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.{Accept, Authorization, `Content-Type`}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{PrivateKey, SecureRandom}
import java.util.Base64
import scala.concurrent.duration.*

/** How a client proves who it is at a token, introspection or revocation
  * endpoint (RFC 6749 §2.3, RFC 7523 §2.2, OAuth 2.1 §2.4).
  *
  * Prefer [[ClientAuth.PrivateKeyJwt]]: the client holds a private key and
  * sends a short-lived signed assertion, so there is no shared secret to leak
  * from either side. It is what the OAuth Client Credentials extension
  * recommends.
  */
sealed trait ClientAuth:
  def clientId: String

  /** The name in `token_endpoint_auth_methods_supported`. */
  def method: String

  /** The form fields and headers to add to a request to `endpoint` at the
    * authorization server `issuer`.
    */
  def credentials[F[_]: Sync](issuer: String, endpoint: String): F[(List[(String, String)], Headers)]

object ClientAuth:

  /** A public client: it says who it is and proves nothing (PKCE does that). */
  final case class Public(clientId: String) extends ClientAuth:
    val method = "none"
    def credentials[F[_]: Sync](issuer: String, endpoint: String) = Sync[F].pure((List("client_id" -> clientId), Headers.empty))

  /** A secret in the request body. */
  final case class SecretPost(clientId: String, secret: String) extends ClientAuth:
    val method = "client_secret_post"
    def credentials[F[_]: Sync](issuer: String, endpoint: String) =
      Sync[F].pure((List("client_id" -> clientId, "client_secret" -> secret), Headers.empty))
    override def toString = s"SecretPost($clientId, ***)"

  /** A secret in HTTP Basic, each part form-encoded first (RFC 6749 §2.3.1). */
  final case class SecretBasic(clientId: String, secret: String) extends ClientAuth:
    val method = "client_secret_basic"
    def credentials[F[_]: Sync](issuer: String, endpoint: String) =
      val encode = (s: String) => URLEncoder.encode(s, UTF_8)
      Sync[F].pure((Nil, Headers(Authorization(BasicCredentials(encode(clientId), encode(secret))))))
    override def toString = s"SecretBasic($clientId, ***)"

  /** A JWT signed with the client's private key (RFC 7523 §2.2): `iss` and
    * `sub` are the client, `aud` the authorization server's issuer
    * (draft-ietf-oauth-rfc7523bis), a minute of life and a random `jti`, so
    * an intercepted assertion is good for one minute at one server.
    *
    * @param keyId the `kid` of the public key registered for the client
    * @param backdate how far before now `iat` is set, for servers that refuse
    *                 an `iat` a moment in their future (Keycloak); `exp` still
    *                 counts from now
    */
  final case class PrivateKeyJwt(
      clientId: String,
      key: PrivateKey,
      algorithm: JwsAlgorithm = JwsAlgorithm.ES256,
      keyId: Option[String] = None,
      lifetimeSeconds: Long = 60,
      audience: PrivateKeyJwt.Audience = PrivateKeyJwt.Audience.Issuer,
      backdate: FiniteDuration = 5.seconds
  ) extends ClientAuth:
    val method = "private_key_jwt"
    def credentials[F[_]: Sync](issuer: String, endpoint: String) =
      Sync[F].realTime.flatMap { now =>
        Sync[F].delay {
          val jti = new Array[Byte](16)
          ClientAuth.random.nextBytes(jti)
          val aud = audience match
            case PrivateKeyJwt.Audience.Issuer   => issuer
            case PrivateKeyJwt.Audience.Endpoint => endpoint
          val claims = Json.obj(
            "iss" -> clientId.asJson,
            "sub" -> clientId.asJson,
            "aud" -> aud.asJson,
            "iat" -> (now.toSeconds - backdate.toSeconds).asJson,
            "exp" -> (now.toSeconds + lifetimeSeconds).asJson,
            "jti" -> Base64.getUrlEncoder.withoutPadding.encodeToString(jti).asJson
          )
          val header    = JsonObject.fromIterable(keyId.map(k => "kid" -> k.asJson).toList :+ ("typ" -> "JWT".asJson))
          val assertion = Jwt.sign(algorithm, key, header, claims)
          (
            List(
              "client_assertion_type" -> "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
              "client_assertion"      -> assertion
            ),
            Headers.empty
          )
        }
      }
    override def toString = s"PrivateKeyJwt($clientId, ${algorithm.name}, ${keyId.getOrElse("-")})"

  object PrivateKeyJwt:
    /** What the assertion's `aud` names. Some servers (older Keycloak) want
      * the endpoint rather than the issuer.
      */
    enum Audience:
      case Issuer, Endpoint

  private val random = new SecureRandom()

  /** Refuses a method the authorization server does not list (the Client
    * Credentials extension's MUST, and a guard against silently sending a
    * secret to a server that expects a signed assertion).
    */
  def check(auth: ClientAuth, supported: Option[List[String]]): Either[OAuthError, Unit] =
    // RFC 8414 §2: when absent, the default is client_secret_basic.
    val methods = supported.getOrElse(List("client_secret_basic"))
    Either.cond(auth.method == "none" || methods.contains(auth.method), (), OAuthError.ClientAuthUnsupported(auth.method, methods))

/** POSTs a form to an OAuth endpoint with the client's credentials, and turns
  * an OAuth error response into [[OAuthError.TokenEndpoint]] instead of a bare
  * status.
  *
  * With a DPoP key, the request carries a proof, and a `use_dpop_nonce`
  * answer (RFC 9449 §8) is retried once with the server's nonce.
  */
object OAuthEndpoint:

  def post[F[_]: Async, A: io.circe.Decoder](
      http: Client[F],
      issuer: String,
      endpoint: String,
      auth: ClientAuth,
      params: List[(String, String)],
      extraHeaders: Headers = Headers.empty,
      dpop: Option[Dpop.Key] = None
  ): F[A] =
    def attempt(uri: Uri, nonce: Option[String]): F[Either[(Option[String], OAuthError), A]] =
      for
        (fields, headers) <- auth.credentials[F](issuer, endpoint)
        proof <- dpop.traverse(_.proof[F]("POST", endpoint, None, nonce))
        request = Request[F](Method.POST, uri)
          .withEntity(UrlForm((params ++ fields)*))
          .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))
          .putHeaders(Accept(MediaType.application.json))
          .putHeaders(proof.map(p => Header.Raw(Dpop.HeaderName, p)).toList)
        authed = request.withHeaders(request.headers ++ headers ++ extraHeaders)
        result <- http.run(authed).use { response =>
          if response.status.isSuccess then response.as[A].map(Right(_))
          else
            response.bodyText.compile.string.map { body =>
              val json = io.circe.parser.parse(body).toOption
              val code = json.flatMap(_.hcursor.get[String]("error").toOption)
              val text = json.flatMap(_.hcursor.get[String]("error_description").toOption)
              Left((response.headers.get(Dpop.NonceHeader).map(_.head.value), OAuthError.TokenEndpoint(response.status.code, code.getOrElse("unknown"), text)))
            }
        }
      yield result

    for
      _   <- ResourceIdentity.requireSecure(endpoint).leftMap(OAuthError.Insecure(_)).liftTo[F]
      uri <- Uri.fromString(endpoint).liftTo[F]
      first <- attempt(uri, None)
      result <- first match
        case Right(a) => Async[F].pure(a)
        case Left((Some(nonce), OAuthError.TokenEndpoint(_, "use_dpop_nonce", _))) if dpop.isDefined =>
          attempt(uri, Some(nonce)).flatMap(_.fold(e => Async[F].raiseError(e._2), Async[F].pure))
        case Left((_, error)) => Async[F].raiseError(error)
    yield result
