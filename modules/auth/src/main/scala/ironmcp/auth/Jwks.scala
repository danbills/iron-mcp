package ironmcp
package auth

import cats.Applicative
import cats.effect.{Ref, Temporal}
import cats.syntax.all.*
import io.circe.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

import java.math.BigInteger
import java.security.spec.RSAPublicKeySpec
import java.security.{KeyFactory, PublicKey}
import java.time.Instant
import scala.concurrent.duration.*

/** JSON Web Key (RFC 7517). */
final case class Jwk(
    kty: String,
    alg: Option[String] = None,
    kid: Option[String] = None,
    use: Option[String] = None,
    n: Option[String] = None,
    e: Option[String] = None
):
  /** Converts an RSA key to a `java.security.PublicKey`. */
  def toPublicKey: Either[String, PublicKey] =
    if kty != "RSA" then Left(s"Unsupported kty: $kty")
    else
      for
        modB64 <- n.toRight("Missing modulus (n)")
        expB64 <- e.toRight("Missing exponent (e)")
        modulus <- scala.util.Try(new BigInteger(1, Jwt.base64UrlDecode(modB64))).toEither.left.map(_.getMessage)
        exponent <- scala.util.Try(new BigInteger(1, Jwt.base64UrlDecode(expB64))).toEither.left.map(_.getMessage)
        key <- scala.util.Try {
          val spec = new RSAPublicKeySpec(modulus, exponent)
          KeyFactory.getInstance("RSA").generatePublic(spec)
        }.toEither.left.map(_.getMessage)
      yield key

object Jwk:
  given Codec[Jwk] = Codec.AsObject.derived

final case class JwksKeySet(keys: List[Jwk])

object JwksKeySet:
  given Codec[JwksKeySet] = Codec.AsObject.derived

/** Resolves public keys for JWT RS256 signature verification. */
trait JwksProvider[F[_]]:
  def key(kid: Option[String]): F[Option[PublicKey]]

object JwksProvider:

  /** Provider using static, in-memory keys (for testing or single-key setups). */
  def staticKeys[F[_]: Applicative](keys: Map[String, PublicKey]): JwksProvider[F] =
    new JwksProvider[F]:
      def key(kid: Option[String]): F[Option[PublicKey]] =
        kid match
          case Some(id) => Applicative[F].pure(keys.get(id))
          case None     => Applicative[F].pure(keys.values.headOption)

  /** Provider from a parsed `JwksKeySet`. */
  def fromJwks[F[_]: Applicative](jwks: JwksKeySet): JwksProvider[F] =
    val map = jwks.keys.flatMap { jwk =>
      jwk.toPublicKey.toOption.flatMap { pk =>
        jwk.kid.map(id => id -> pk)
      }
    }.toMap
    staticKeys(map)

  /** Keycloak dynamic JWKS provider with TTL caching and cache invalidation
    * when an unknown `kid` is encountered.
    */
  def keycloak[F[_]: Temporal](
      client: Client[F],
      jwksUri: Uri,
      cacheTtl: FiniteDuration = 1.hour
  ): F[JwksProvider[F]] =
    Ref.of[F, Option[(Instant, Map[String, PublicKey])]](None).map { cache =>
      new JwksProvider[F]:
        private def fetchKeys: F[Map[String, PublicKey]] =
          client.expect[JwksKeySet](jwksUri).map { jwks =>
            jwks.keys.flatMap { jwk =>
              jwk.toPublicKey.toOption.flatMap { pk =>
                jwk.kid.map(id => id -> pk)
              }
            }.toMap
          }

        def key(kid: Option[String]): F[Option[PublicKey]] =
          for
            now <- Temporal[F].realTimeInstant
            cached <- cache.get
            keys <- cached match
              case Some((expiresAt, map)) if now.isBefore(expiresAt) && kid.forall(map.contains) =>
                Applicative[F].pure(map)
              case _ =>
                fetchKeys.flatMap { fresh =>
                  cache.set(Some((now.plusSeconds(cacheTtl.toSeconds), fresh))).as(fresh)
                }
          yield kid match
            case Some(id) => keys.get(id)
            case None     => keys.values.headOption
    }
