package ironmcp
package auth

import cats.effect.{Ref, Temporal}
import cats.syntax.all.*
import io.circe.JsonObject
import ironmcp.client.{McpClient, McpClientError}
import ironmcp.protocol.*
import org.http4s.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** A client that keeps an access token current and steps up when a server
  * asks for more (SEP-2350).
  *
  * On `403 insufficient_scope` it asks for the **union** of what it asked for
  * before and what the challenge names, then retries:
  *
  *   `requested_new = requested_previous ∪ challenged`
  *
  * Asking for the missing scope alone would replace the token with one that
  * lacks the scopes that already worked.
  *
  * A server picks the scopes in its challenge, so a hostile or compromised
  * server could ask the client to request far more than it needs. With
  * `allowedScopes`, a challenge naming anything outside that set is refused
  * ([[StepUpClient.ScopeNotAllowed]]) rather than sent to the authorization
  * server.
  *
  * A DPoP token (`token_type: DPoP`) is sent as `Authorization: DPoP`; route
  * the HTTP client through [[Dpop.client]] to add the proofs. A token about to
  * expire is renewed before it is sent.
  */
final class StepUpClient[F[_]: Temporal] private (
    stateRef: Ref[F, StepUpClient.State],
    authorizeFn: Set[String] => F[TokenResponse],
    strategy: ScopeStrategy,
    maxRetries: Int,
    allowedScopes: Option[Set[String]],
    renewBefore: FiniteDuration
):

  def state: F[StepUpClient.State] = stateRef.get

  /** Acquires an initial token for the specified scopes. */
  def initialAuthorize(scopes: Set[String]): F[TokenResponse] =
    authorizeFn(scopes).flatTap(resp => hold(resp, requested = scopes))

  /** The `Authorization` header for the token held, renewed first if it
    * expires within `renewBefore`.
    */
  def authHeaders: F[Headers] =
    for
      now <- Temporal[F].realTime.map(_.toSeconds)
      s   <- stateRef.get
      _ <- (s.currentToken, s.expiresAt) match
        case (Some(_), Some(at)) if at - renewBefore.toSeconds <= now =>
          authorizeFn(s.requestedScopes).flatMap(resp => hold(resp, s.requestedScopes)).void
        case _ => Temporal[F].unit
      held <- stateRef.get
    yield held.currentToken match
      case Some(token) => Headers(Authorization(Credentials.Token(CIString(held.scheme), token)))
      case None        => Headers.empty

  /** Invokes a tool by name through `client`, handling step-up challenges automatically. */
  def callTool(client: McpClient[F], toolName: String, arguments: JsonObject = JsonObject.empty): F[CallToolResult | InputRequiredResult] =
    executeWithRetry(client.callTool(toolName, arguments), toolName, retriesLeft = maxRetries)

  /** Executes an arbitrary client operation with step-up handling. */
  def executeWithRetry[A](action: F[A], opName: String, retriesLeft: Int): F[A] =
    action.recoverWith {
      case err: McpClientError.Http if err.status == 403 && retriesLeft > 0 =>
        handleScopeChallenge(err, opName) *> executeWithRetry(action, opName, retriesLeft - 1)
      case err: McpClientError.Http if err.status == 401 && retriesLeft > 0 =>
        reauthorize(err) *> executeWithRetry(action, opName, retriesLeft - 1)
    }

  private def hold(resp: TokenResponse, requested: Set[String]): F[Unit] =
    Temporal[F].realTime.flatMap { now =>
      stateRef.update(
        _.copy(
          requestedScopes = requested,
          grantedScopes = resp.grantedScopes,
          currentToken = Some(resp.access_token),
          scheme = if resp.token_type.equalsIgnoreCase("DPoP") then "DPoP" else "Bearer",
          expiresAt = resp.expires_in.map(now.toSeconds + _)
        )
      )
    }

  private def handleScopeChallenge(err: McpClientError.Http, opName: String): F[Unit] =
    val challenged = StepUpClient.parseChallengeScopes(err.wwwAuthenticate)
    if challenged.isEmpty then Temporal[F].raiseError(err)
    else
      allowedScopes.map(challenged -- _).filter(_.nonEmpty) match
        case Some(outside) => Temporal[F].raiseError(StepUpClient.ScopeNotAllowed(opName, outside))
        case None =>
          for
            s <- stateRef.get
            target = strategy match
              case ScopeStrategy.Union => s.requestedScopes ++ challenged
              case ScopeStrategy.Naive => challenged -- s.grantedScopes
            resp <- authorizeFn(target)
            withheld = target -- resp.grantedScopes
            _ <-
              if withheld.nonEmpty && strategy == ScopeStrategy.Union then
                Temporal[F].raiseError[Unit](StepUpClient.NarrowerGrantException(opName, withheld, resp.grantedScopes))
              else hold(resp, target)
          yield ()

  /** A `401`: the token was refused (expired, revoked). Get a new one for
    * the same scopes, or for those the challenge names when none were asked
    * for yet.
    */
  private def reauthorize(err: McpClientError.Http): F[Unit] =
    stateRef.get.flatMap { s =>
      val challenged = AuthChallenge.parse(err.wwwAuthenticate).flatMap(_.scopes).toSet
      val wanted     = if s.requestedScopes.nonEmpty then s.requestedScopes else challenged
      allowedScopes.map(wanted -- _).filter(_.nonEmpty) match
        case Some(outside) => Temporal[F].raiseError(StepUpClient.ScopeNotAllowed("re-authorization", outside))
        case None          => authorizeFn(wanted).flatMap(resp => hold(resp, wanted))
    }

object StepUpClient:

  final case class State(
      requestedScopes: Set[String] = Set.empty,
      grantedScopes: Set[String] = Set.empty,
      currentToken: Option[String] = None,
      scheme: String = "Bearer",
      expiresAt: Option[Long] = None
  )

  final case class NarrowerGrantException(operation: String, withheldScopes: Set[String], grantedScopes: Set[String])
      extends Exception(
        s"Authorization server withheld scopes [${withheldScopes.mkString(" ")}] required for '$operation'. " +
          s"Granted only [${grantedScopes.mkString(" ")}]. Narrower grants cannot be retried automatically."
      )

  final case class ScopeNotAllowed(operation: String, scopes: Set[String])
      extends Exception(s"the server asked for [${scopes.toList.sorted.mkString(" ")}] for '$operation', outside what this client may request")

  /** The scopes of the `insufficient_scope` challenges among `headers`. */
  def parseChallengeScopes(headers: List[String]): Set[String] =
    AuthChallenge.parse(headers).filter(_.error.contains("insufficient_scope")).flatMap(_.scopes).toSet

  /** A client following the Scope-Union Rule. */
  def create[F[_]: Temporal](
      authorize: Set[String] => F[TokenResponse],
      strategy: ScopeStrategy = ScopeStrategy.Union,
      maxRetries: Int = 2,
      allowedScopes: Option[Set[String]] = None,
      renewBefore: FiniteDuration = 30.seconds
  ): F[StepUpClient[F]] =
    Ref.of[F, State](State()).map(ref => new StepUpClient[F](ref, authorize, strategy, maxRetries, allowedScopes, renewBefore))
