package ironmcp
package auth

import cats.{Applicative, Monad}
import cats.effect.{Clock, Ref, Sync}
import cats.syntax.all.*

/** Whether a token that passed every check has since been revoked. Added to a
  * validator with [[TokenValidator.withRevocation]].
  */
trait RevocationCheck[F[_]]:
  def revoked(claims: TokenClaims): F[Option[AuthError]]

object RevocationCheck:
  def none[F[_]: Applicative]: RevocationCheck[F] = _ => Option.empty[AuthError].pure[F]

/** An in-memory deny-list: single tokens by `jti`, login sessions by `sid`,
  * and everything a subject was issued before a moment (a "log out
  * everywhere"). Entries are dropped once every token they could match has
  * expired anyway.
  *
  * It lives in one process. Several replicas need a shared store (implement
  * [[RevocationCheck]] over it) or [[Introspection]].
  */
final class RevocationList[F[_]: Monad] private (state: Ref[F, RevocationList.State], clock: Clock[F]) extends RevocationCheck[F]:
  import RevocationList.*

  /** Refuses the token with this `jti` until `until` (its `exp`). */
  def revokeToken(jti: String, until: Long): F[Unit] = change(s => s.copy(tokens = s.tokens.updated(jti, until)))

  /** Refuses every token of this login session until `until`. */
  def revokeSession(sid: String, until: Long): F[Unit] = change(s => s.copy(sessions = s.sessions.updated(sid, until)))

  /** Refuses every token issued to `sub` (by `iss`) before `before`, until
    * `until`. A token without `iat` counts as issued before.
    */
  def revokeSubject(iss: String, sub: String, before: Long, until: Long): F[Unit] =
    change(s => s.copy(subjects = s.subjects.updated((iss, sub), (before, until))))

  /** Revokes the session of `claims` if it has one, else the token by its
    * `jti`, else every token its subject holds so far.
    */
  def revoke(claims: TokenClaims): F[Unit] =
    claims.sid match
      case Some(sid) => revokeSession(sid, claims.exp)
      case None      => claims.jti.fold(revokeSubject(claims.iss, claims.sub.getOrElse(""), claims.iat.getOrElse(claims.exp) + 1, claims.exp))(revokeToken(_, claims.exp))

  def revoked(claims: TokenClaims): F[Option[AuthError]] =
    state.get.map { s =>
      claims.jti.filter(s.tokens.contains).map(_ => AuthError.Revoked("token"))
        .orElse(claims.sid.filter(s.sessions.contains).map(_ => AuthError.Revoked("session")))
        .orElse(
          claims.sub.flatMap(sub => s.subjects.get((claims.iss, sub))).collect {
            case (before, _) if claims.iat.forall(_ < before) => AuthError.Revoked("subject")
          }
        )
    }

  private def change(f: State => State): F[Unit] =
    clock.realTime.flatMap(now => state.update(s => f(s).prune(now.toSeconds)))

object RevocationList:
  private[auth] final case class State(
      tokens: Map[String, Long] = Map.empty,
      sessions: Map[String, Long] = Map.empty,
      subjects: Map[(String, String), (Long, Long)] = Map.empty
  ):
    def prune(now: Long): State =
      State(tokens.filter(_._2 >= now), sessions.filter(_._2 >= now), subjects.filter(_._2._2 >= now))

  def inMemory[F[_]: Sync]: F[RevocationList[F]] =
    Ref.of[F, State](State()).map(new RevocationList(_, Clock[F]))
