package ironmcp
package auth

import cats.effect.Sync
import cats.syntax.all.*
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Headers}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.concurrent.duration.*

/** How a registered client proves who it is at a token endpoint. */
sealed trait ClientCredential:
  def method: String

object ClientCredential:

  /** A shared secret, kept only as its SHA-256 and compared in constant time.
    * Build one with [[Secret.of]], which refuses short secrets.
    */
  final class Secret private (digest: Array[Byte]) extends ClientCredential:
    val method = "client_secret_basic"
    def matches(candidate: String): Boolean = MessageDigest.isEqual(digest, Secret.sha256(candidate))
    override def toString = "Secret(***)"

  object Secret:
    /** At least 32 characters: a secret a person typed is not a client secret. */
    val MinLength = 32
    def of(secret: String): Either[String, Secret] =
      Either.cond(secret.length >= MinLength, new Secret(sha256(secret)), s"a client secret needs at least $MinLength characters")
    private[ClientCredential] def sha256(s: String): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8))

  /** `private_key_jwt` (RFC 7523 §2.2) with the client's registered public keys. */
  final case class Keys(keys: KeySet, algorithms: Set[JwsAlgorithm] = JwsAlgorithm.asymmetric) extends ClientCredential:
    val method = "private_key_jwt"

/** Client authentication at a token endpoint written in Scala: the server
  * side of [[ClientAuth]].
  *
  *   - exactly one method per request (RFC 6749 §2.3): a request carrying a
  *     Basic header and a `client_assertion` is refused, not tried twice
  *   - each client is registered with one credential, and only that method
  *     works for it
  *   - assertions: `iss` = `sub` = the client, `aud` names this server's
  *     issuer or token endpoint, `exp` at most [[maxAssertionLifetime]] away,
  *     a `jti` that is never accepted twice
  *
  * Failures come back as `(error, detail)`: the error is the OAuth code to
  * send; the detail is for the audit log, never for the response.
  *
  * @param issuer   this server's issuer: an assertion's `aud`
  * @param endpoint this token endpoint's URL: also accepted as `aud`
  */
final class ClientRegistry[F[_]: Sync](
    clients: Map[String, ClientCredential],
    issuer: String,
    endpoint: String,
    replay: ReplayCache[F],
    maxAssertionLifetime: FiniteDuration = 5.minutes,
    clockSkew: FiniteDuration = 60.seconds
):
  import ClientRegistry.*

  def methods: List[String] = clients.values.map(_.method).toList.distinct.sorted

  /** The id of the client that authenticated this request. */
  def authenticate(form: Map[String, String], headers: Headers): F[Either[Failure, String]] =
    val basic = headers.get[Authorization].collect { case Authorization(BasicCredentials(user, password)) => (decode(user), decode(password)) }
    val post  = (form.get("client_id"), form.get("client_secret")).tupled.filter(_ => form.contains("client_secret"))
    val jwt   = form.get("client_assertion")
    List(basic.isDefined, form.contains("client_secret"), jwt.isDefined).count(identity) match
      case 0 => Sync[F].pure(Left(Failure("invalid_client", "no client authentication")))
      case n if n > 1 => Sync[F].pure(Left(Failure("invalid_request", "more than one client authentication method")))
      case _ =>
        (basic.orElse(post), jwt) match
          case (Some((id, secret)), _) =>
            Sync[F].pure(
              for
                _ <- Either.cond(form.get("client_id").forall(_ == id), (), Failure("invalid_request", "client_id differs from the authenticated client"))
                _ <- clients.get(id) match
                  case Some(s: ClientCredential.Secret) if s.matches(secret) => Right(())
                  case Some(_: ClientCredential.Secret)                     => Left(Failure("invalid_client", s"wrong secret for $id"))
                  case Some(other)                                          => Left(Failure("invalid_client", s"$id must authenticate with ${other.method}"))
                  case None                                                 => Left(Failure("invalid_client", s"unknown client $id"))
              yield id
            )
          case (None, Some(assertion)) => verifyAssertion(assertion, form)
          case (None, None)            => Sync[F].pure(Left(Failure("invalid_client", "no client authentication")))

  private def verifyAssertion(assertion: String, form: Map[String, String]): F[Either[Failure, String]] =
    val checked =
      for
        _ <- Either.cond(form.get("client_assertion_type").contains(AssertionType), (), Failure("invalid_client", "client_assertion_type is not jwt-bearer"))
        parts <- Jwt.parse(assertion).leftMap(e => Failure("invalid_client", e.description))
        c   = parts.payload.hcursor
        id <- c.get[String]("sub").leftMap(_ => Failure("invalid_client", "assertion has no sub"))
        _  <- Either.cond(c.get[String]("iss").toOption.contains(id), (), Failure("invalid_client", "assertion iss is not its sub"))
        _  <- Either.cond(form.get("client_id").forall(_ == id), (), Failure("invalid_request", "client_id differs from the assertion's sub"))
        keys <- clients.get(id) match
          case Some(k: ClientCredential.Keys) => Right(k)
          case Some(other)                    => Left(Failure("invalid_client", s"$id must authenticate with ${other.method}"))
          case None                           => Left(Failure("invalid_client", s"unknown client $id"))
        alg <- Jwt.algorithm(parts, keys.algorithms.filter(_.isAsymmetric)).leftMap(e => Failure("invalid_client", e.description))
        key <- keys.keys.select(parts.keyId, alg).leftMap(m => Failure("invalid_client", s"no registered key for $id ($m)"))
        _   <- Jwt.verify(parts, key.key, Set(alg)).leftMap(e => Failure("invalid_client", e.description))
        aud = c.downField("aud").as[List[String]].orElse(c.get[String]("aud").map(List(_))).getOrElse(Nil)
        _   <- Either.cond(aud.exists(a => a == issuer || a == endpoint), (), Failure("invalid_client", s"assertion aud ${aud.mkString(" ")} names neither $issuer nor $endpoint"))
        exp <- c.get[Long]("exp").leftMap(_ => Failure("invalid_client", "assertion has no exp"))
        jti <- c.get[String]("jti").leftMap(_ => Failure("invalid_client", "assertion has no jti"))
      yield (id, exp, jti)
    checked match
      case Left(f) => Sync[F].pure(Left(f))
      case Right((id, exp, jti)) =>
        Sync[F].realTime.map(_.toSeconds).flatMap { now =>
          if exp + clockSkew.toSeconds < now then Sync[F].pure(Left(Failure("invalid_client", "assertion expired")))
          else if exp - now > (maxAssertionLifetime + clockSkew).toSeconds then Sync[F].pure(Left(Failure("invalid_client", s"assertion lives longer than $maxAssertionLifetime")))
          else replay.firstUse(s"client-assertion:$id:$jti", exp + clockSkew.toSeconds).map(first => Either.cond(first, id, Failure("invalid_client", "assertion replayed")))
        }

  private def decode(s: String) = URLDecoder.decode(s, UTF_8)

object ClientRegistry:
  val AssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"

  /** `error` is the OAuth code for the response; `detail` is for the audit log. */
  final case class Failure(error: String, detail: String)

  def apply[F[_]: Sync](clients: Map[String, ClientCredential], issuer: String, endpoint: String, replay: ReplayCache[F]): ClientRegistry[F] =
    new ClientRegistry[F](clients, issuer, endpoint, replay)
