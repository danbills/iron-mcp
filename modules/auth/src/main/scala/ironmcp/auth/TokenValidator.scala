package ironmcp
package auth

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.all.*

import java.security.PublicKey

/** Validates Bearer JWTs at the Resource Server.
  *
  * Enforces cryptographic signature (RS256 via JWKS or HS256 via HMAC secret),
  * token expiration, optional issuer matching, and **RFC 8707 audience binding**.
  */
trait TokenValidator[F[_]]:
  def validate(token: String): F[Either[AuthError, TokenClaims]]

object TokenValidator:

  final case class Config(
      expectedResource: Option[String] = None,
      expectedIssuer: Option[String] = None,
      clockSkewSeconds: Long = 60L
  )

  /** Creates a validator backed by RS256 and a [[JwksProvider]]. */
  def rs256[F[_]: {Clock, MonadThrow}](
      jwks: JwksProvider[F],
      config: Config = Config()
  ): TokenValidator[F] = new TokenValidator[F]:
    def validate(token: String): F[Either[AuthError, TokenClaims]] =
      Jwt.parse(token) match
        case Left(err) => MonadThrow[F].pure(Left(err))
        case Right(parts) =>
          jwks.key(parts.keyId).flatMap {
            case None =>
              MonadThrow[F].pure(Left(AuthError.InvalidSignature(s"Key '${parts.keyId.getOrElse("default")}' not found in JWKS")))
            case Some(publicKey: PublicKey) =>
              for
                now <- Clock[F].realTime.map(_.toSeconds)
                res = Jwt.verifyRs256(parts, publicKey).flatMap(claims => checkClaims(claims, now, config))
              yield res
          }

  /** Creates a validator backed by HS256 and a shared secret (useful for testing and offline demos). */
  def hs256[F[_]: {Clock, MonadThrow}](
      secret: Array[Byte],
      config: Config = Config()
  ): TokenValidator[F] = new TokenValidator[F]:
    def validate(token: String): F[Either[AuthError, TokenClaims]] =
      for
        now <- Clock[F].realTime.map(_.toSeconds)
        res = Jwt.parse(token)
          .flatMap(parts => Jwt.verifyHs256(parts, secret))
          .flatMap(claims => checkClaims(claims, now, config))
      yield res

  private def checkClaims(claims: TokenClaims, now: Long, config: Config): Either[AuthError, TokenClaims] =
    val expCheck = claims.exp match
      case Some(exp) if exp + config.clockSkewSeconds < now =>
        Left(AuthError.Expired(exp, now))
      case _ => Right(())

    val nbfCheck = claims.nbf match
      case Some(nbf) if nbf - config.clockSkewSeconds > now =>
        Left(AuthError.Premature(nbf, now))
      case _ => Right(())

    val issCheck = config.expectedIssuer match
      case Some(iss) if claims.iss != iss =>
        Left(AuthError.IssuerMismatch(iss, claims.iss))
      case _ => Right(())

    val audCheck = config.expectedResource match
      case Some(resource) if !claims.aud.contains(resource) =>
        Left(AuthError.WrongAudience(resource, claims.aud))
      case _ => Right(())

    for
      _ <- expCheck
      _ <- nbfCheck
      _ <- issCheck
      _ <- audCheck
    yield claims
