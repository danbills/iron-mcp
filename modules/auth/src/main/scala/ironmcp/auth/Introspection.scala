package ironmcp
package auth

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import io.circe.Json
import org.http4s.client.Client

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.duration.*

/** Token validation by asking the authorization server (RFC 7662).
  *
  * Slower than checking a JWT locally, but a revoked token stops working as
  * soon as the cache entry lapses, rather than when the token expires, and it
  * works for opaque tokens. Use it alone, or for high-risk calls only with
  * [[TokenValidator.layered]].
  *
  * The resource server authenticates to the endpoint as a client of its own
  * (`auth`). Answers are cached by the SHA-256 of the token, never the token
  * itself, for at most `cacheTtl` and never past the token's `exp`. Any
  * failure to get an answer is a refusal ([[AuthError.KeysUnavailable]],
  * `503`), never a pass.
  */
object Introspection:

  final case class Config(
      issuer: String,
      audience: String,
      endpoint: String,
      auth: ClientAuth,
      cacheTtl: FiniteDuration = 30.seconds,
      clockSkew: FiniteDuration = 60.seconds,
      maxCacheEntries: Int = 10000
  )

  def apply[F[_]: Async](http: Client[F], config: Config): F[TokenValidator[F]] =
    ResourceIdentity.requireSecure(config.endpoint).leftMap(OAuthError.Insecure(_)).liftTo[F] *>
      Ref.of[F, Map[String, (Either[AuthError, TokenClaims], Long)]](Map.empty).map { cache =>
        token =>
          for
            now <- Async[F].realTime.map(_.toSeconds)
            key  = Base64.getUrlEncoder.withoutPadding.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)))
            hit <- cache.get.map(_.get(key).filter(_._2 > now).map(_._1))
            result <- hit match
              case Some(cached) => cached.pure[F]
              case None =>
                ask(http, config, token)
                  .map(json => judge(json, now, config))
                  .handleError(e => Left(AuthError.KeysUnavailable(s"introspection: ${e.getMessage}")))
                  .flatTap {
                    case Left(AuthError.KeysUnavailable(_)) => Async[F].unit // never cache an outage
                    case answer =>
                      val until = answer.fold(_ => now + config.cacheTtl.toSeconds, c => math.min(c.exp, now + config.cacheTtl.toSeconds))
                      cache.update { m =>
                        val live = if m.size >= config.maxCacheEntries then m.filter(_._2._2 > now) else m
                        (if live.size >= config.maxCacheEntries then Map.empty else live).updated(key, (answer, until))
                      }
                  }
          yield result
      }

  private def ask[F[_]: Async](http: Client[F], config: Config, token: String): F[Json] =
    OAuthEndpoint.post[F, Json](http, config.issuer, config.endpoint, config.auth, List("token" -> token, "token_type_hint" -> "access_token"))

  /** The answer, checked as strictly as a local JWT would be. */
  private[auth] def judge(json: Json, now: Long, config: Config): Either[AuthError, TokenClaims] =
    val c = json.hcursor
    val skew = config.clockSkew.toSeconds
    if !c.get[Boolean]("active").getOrElse(false) then Left(AuthError.Inactive)
    else
      // `iss` may be left out: the endpoint speaks for its own issuer.
      val withIss = if c.downField("iss").succeeded then json else json.deepMerge(Json.obj("iss" -> Json.fromString(config.issuer)))
      for
        _      <- Either.cond(c.downField("exp").succeeded, (), AuthError.MissingClaim("exp"))
        claims <- withIss.as[TokenClaims].leftMap(e => AuthError.MalformedToken(e.message))
        _      <- Either.cond(claims.iss == config.issuer, (), AuthError.IssuerMismatch(config.issuer, claims.iss))
        _      <- Either.cond(claims.aud.contains(config.audience), (), AuthError.WrongAudience(config.audience, claims.aud))
        _      <- Either.cond(claims.exp + skew >= now, (), AuthError.Expired(claims.exp, now))
        _      <- claims.nbf.traverse_(nbf => Either.cond(nbf - skew <= now, (), AuthError.Premature(nbf, now)))
      yield claims
