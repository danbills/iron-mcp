package ironmcp
package auth

import cats.{Monad, MonadThrow}
import cats.effect.Clock
import cats.syntax.all.*
import io.circe.Json

import scala.concurrent.duration.*

/** Checks an access token at the resource server and returns its claims.
  *
  * Build one with [[TokenValidator.jwks]] (public-key JWTs from the
  * authorization server's JWKS), [[TokenValidator.sharedSecret]] (HS256, tests
  * only) or [[Introspection]] (RFC 7662), and add checks with
  * [[withRevocation]] or [[TokenValidator.layered]].
  */
trait TokenValidator[F[_]]:
  def validate(token: String): F[Either[AuthError, TokenClaims]]

  /** Also refuses tokens `check` says are revoked. */
  def withRevocation(check: RevocationCheck[F])(using Monad[F]): TokenValidator[F] =
    val self = this
    token =>
      self.validate(token).flatMap {
        case Right(claims) => check.revoked(claims).map(_.toLeft(claims))
        case left          => left.pure[F]
      }

object TokenValidator:

  /** What makes a JWT an access token rather than some other token from the
    * same issuer. Without this, an OIDC ID token (same `iss`, and often an
    * `aud` you would accept) could be presented as an access token.
    */
  final case class AccessTokenProfile(name: String, accepts: (Jwt.JwtParts, Json) => Either[String, Unit])

  object AccessTokenProfile:
    /** RFC 9068: header `typ` is `at+jwt`. */
    val rfc9068: AccessTokenProfile = AccessTokenProfile(
      "rfc9068",
      (parts, _) => Either.cond(parts.typ.contains("at+jwt"), (), s"header typ is '${parts.typ.getOrElse("")}', not at+jwt")
    )

    /** Keycloak: payload `typ` is `Bearer`, or `DPoP` for a DPoP-bound token
      * (its ID tokens say `ID`, refresh tokens `Refresh`). A `DPoP` token
      * must carry the `cnf.jkt` it is bound to.
      */
    val keycloak: AccessTokenProfile = AccessTokenProfile(
      "keycloak",
      (_, payload) =>
        val c   = payload.hcursor
        val typ = c.get[String]("typ").toOption
        typ match
          case Some("Bearer") => Right(())
          case Some("DPoP")   => Either.cond(c.downField("cnf").get[String]("jkt").isRight, (), "payload typ is DPoP but the token has no cnf.jkt")
          case other          => Left(s"payload typ is '${other.getOrElse("")}', not Bearer or DPoP")
    )

    /** Either of the above: the default. */
    val standard: AccessTokenProfile = AccessTokenProfile(
      "standard",
      (parts, payload) => rfc9068.accepts(parts, payload).orElse(keycloak.accepts(parts, payload)).leftMap(_ => "neither at+jwt nor a Keycloak Bearer token")
    )

  /** What every token must satisfy. `issuer` and `audience` have no default:
    * a validator that does not know whom it trusts and who it is refuses
    * nothing that matters.
    *
    * @param issuer       the exact `iss` to accept
    * @param audience     the exact `aud` to require: this server's resource URI
    * @param algorithms   accepted signature algorithms; the token's `alg` must be one
    * @param profile      what makes the JWT an access token
    * @param clockSkew    tolerance on `exp`, `nbf` and `iat`
    * @param maxAge       if set, refuse tokens issued longer ago than this
    * @param maxTokenBytes tokens larger than this are refused before parsing
    */
  final case class Config(
      issuer: String,
      audience: String,
      algorithms: Set[JwsAlgorithm] = JwsAlgorithm.asymmetric,
      profile: AccessTokenProfile = AccessTokenProfile.standard,
      clockSkew: FiniteDuration = 60.seconds,
      maxAge: Option[FiniteDuration] = None,
      maxTokenBytes: Int = Jwt.DefaultMaxBytes
  )

  object Config:
    /** The config for tokens issued by `issuer` to the resource `resource`. */
    def apply(issuer: String, resource: ResourceIdentity): Config = Config(issuer, resource.resource)

  /** JWTs signed with keys from `keys` (usually [[JwksProvider.remote]]). */
  def jwks[F[_]: {Clock, MonadThrow}](keys: JwksProvider[F], config: Config): TokenValidator[F] =
    val allowed = config.algorithms.filter(_.isAsymmetric)
    token =>
      Jwt.parse(token, config.maxTokenBytes).flatMap(parts => Jwt.algorithm(parts, allowed).map(parts -> _)) match
        case Left(error) => error.asLeft[TokenClaims].pure[F]
        case Right((parts, alg)) =>
          keys.select(parts.keyId, alg).flatMap {
            case Left(error) => error.asLeft[TokenClaims].pure[F]
            case Right(key) =>
              Clock[F].realTime.map(now => Jwt.verify(parts, key.key, Set(alg)).flatMap(_ => checkClaims(parts, now.toSeconds, config)))
          }

  /** HS256 with a shared secret. Whoever can check these tokens can mint them,
    * so this is for tests and single-process demos, never for tokens from an
    * authorization server.
    */
  def sharedSecret[F[_]: {Clock, MonadThrow}](secret: Array[Byte], config: Config): TokenValidator[F] =
    val key = Jwt.hmacKey(secret)
    token =>
      Clock[F].realTime.map { now =>
        for
          parts <- Jwt.parse(token, config.maxTokenBytes)
          _     <- Jwt.verify(parts, key, Set(JwsAlgorithm.HS256))
          claims <- checkClaims(parts, now.toSeconds, config)
        yield claims
      }

  /** Checks locally with `local`, then asks `remote` (usually
    * [[Introspection]]) as well when `alsoAsk` holds: for example for tokens
    * carrying an admin scope, where a revocation must take effect at once
    * rather than when the token expires.
    */
  def layered[F[_]: Monad](local: TokenValidator[F], remote: TokenValidator[F])(alsoAsk: TokenClaims => Boolean): TokenValidator[F] =
    token =>
      local.validate(token).flatMap {
        case Right(claims) if alsoAsk(claims) => remote.validate(token).map(_.as(claims))
        case other                            => other.pure[F]
      }

  /** The claim checks shared by every JWT validator. */
  private[auth] def checkClaims(parts: Jwt.JwtParts, now: Long, config: Config): Either[AuthError, TokenClaims] =
    val skew = config.clockSkew.toSeconds
    val payload = parts.payload.hcursor
    for
      _      <- config.profile.accepts(parts, parts.payload).leftMap(AuthError.WrongTokenType(_))
      _      <- Either.cond(payload.downField("iss").succeeded, (), AuthError.MissingClaim("iss"))
      _      <- Either.cond(payload.downField("exp").succeeded, (), AuthError.MissingClaim("exp"))
      claims <- parts.payload.as[TokenClaims].leftMap(e => AuthError.MalformedToken(e.message))
      _      <- Either.cond(claims.iss == config.issuer, (), AuthError.IssuerMismatch(config.issuer, claims.iss))
      _      <- Either.cond(claims.aud.contains(config.audience), (), AuthError.WrongAudience(config.audience, claims.aud))
      _      <- Either.cond(claims.exp + skew >= now, (), AuthError.Expired(claims.exp, now))
      _      <- claims.nbf.traverse_(nbf => Either.cond(nbf - skew <= now, (), AuthError.Premature(nbf, now)))
      _      <- claims.iat.traverse_(iat => Either.cond(iat - skew <= now, (), AuthError.IssuedInFuture(iat, now)))
      _ <- config.maxAge.traverse_ { age =>
        claims.iat match
          case None      => Left(AuthError.MissingClaim("iat"))
          case Some(iat) => Either.cond(now - iat <= age.toSeconds + skew, (), AuthError.TooOld(iat, now))
      }
    yield claims
