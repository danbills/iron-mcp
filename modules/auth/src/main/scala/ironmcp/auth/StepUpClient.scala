package ironmcp
package auth

import cats.effect.{Concurrent, Ref}
import cats.syntax.all.*
import io.circe.JsonObject
import ironmcp.client.{McpClient, McpClientError}
import ironmcp.protocol.*
import org.http4s.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString

import scala.util.matching.Regex

/** Client implementing OAuth 2.1 Step-Up Authorization and the
  * **Scope-Union Rule** (§9.7.4, SEP-2350).
  *
  * When a tool call encounters an HTTP 403 `insufficient_scope` challenge, the client
  * re-authorizes with the Authorization Server for the **union** of the scopes
  * it has already requested and the scopes the challenge names:
  *
  *   `requestedScopes_new = requestedScopes_previous ∪ challengeScopes`
  *
  * This prevents the `--naive` regression where requesting only the missing scopes
  * replaces the token and breaks previously working tools.
  */
final class StepUpClient[F[_]: Concurrent] private (
    stateRef: Ref[F, StepUpClient.State],
    authorizeFn: Set[String] => F[TokenResponse],
    strategy: ScopeStrategy,
    maxRetries: Int
):

  def state: F[StepUpClient.State] = stateRef.get

  /** Acquires an initial token for the specified scopes. */
  def initialAuthorize(scopes: Set[String]): F[TokenResponse] =
    for
      resp <- authorizeFn(scopes)
      _ <- stateRef.update { s =>
        s.copy(
          requestedScopes = scopes,
          grantedScopes = resp.grantedScopes,
          currentToken = Some(resp.access_token)
        )
      }
    yield resp

  /** Current HTTP Authorization header if a token is held. */
  def authHeaders: F[Headers] =
    stateRef.get.map { s =>
      s.currentToken match
        case Some(token) => Headers(Authorization(Credentials.Token(CIString("Bearer"), token)))
        case None        => Headers.empty
    }

  /** Invokes a tool by name through `client`, handling step-up challenges automatically. */
  def callTool(
      client: McpClient[F],
      toolName: String,
      arguments: JsonObject = JsonObject.empty
  ): F[CallToolResult | InputRequiredResult] =
    executeWithRetry(client.callTool(toolName, arguments), toolName, retriesLeft = maxRetries)

  /** Executes an arbitrary client operation with step-up handling. */
  def executeWithRetry[A](
      action: F[A],
      opName: String,
      retriesLeft: Int
  ): F[A] =
    action.recoverWith {
      case err: McpClientError.Http if err.status == 403 && retriesLeft > 0 =>
        handleScopeChallenge(err, opName).flatMap(_ => executeWithRetry(action, opName, retriesLeft - 1))
      case err: McpClientError.Http if err.status == 401 && retriesLeft > 0 =>
        handle401Challenge(err, opName).flatMap(_ => executeWithRetry(action, opName, retriesLeft - 1))
    }

  private def handleScopeChallenge(err: McpClientError.Http, opName: String): F[Unit] =
    val challengeScopes = StepUpClient.parseChallengeScopes(err.wwwAuthenticate)
    if challengeScopes.isEmpty then
      Concurrent[F].raiseError(err)
    else
      for
        s <- stateRef.get
        targetScopes = strategy match
          case ScopeStrategy.Union =>
            // THE UNION RULE: requested ∪ challenged
            s.requestedScopes ++ challengeScopes
          case ScopeStrategy.Naive =>
            // THE ANTI-PATTERN: request only the missing scopes
            challengeScopes -- s.grantedScopes
        resp <- authorizeFn(targetScopes)
        // Check for narrower grant
        withheld = targetScopes -- resp.grantedScopes
        _ <- if withheld.nonEmpty && strategy == ScopeStrategy.Union then
          Concurrent[F].raiseError[Unit](
            StepUpClient.NarrowerGrantException(opName, withheld, resp.grantedScopes)
          )
        else
          stateRef.update { prev =>
            prev.copy(
              requestedScopes = targetScopes,
              grantedScopes = resp.grantedScopes,
              currentToken = Some(resp.access_token)
            )
          }
      yield ()

  private def handle401Challenge(err: McpClientError.Http, opName: String): F[Unit] =
    val _ = (err, opName)
    for
      s <- stateRef.get
      // Re-authorize with the current requested scopes or minimal scopes
      scopesToRequest = if s.requestedScopes.nonEmpty then s.requestedScopes else Set("openid")
      resp <- authorizeFn(scopesToRequest)
      _ <- stateRef.update { prev =>
        prev.copy(
          grantedScopes = resp.grantedScopes,
          currentToken = Some(resp.access_token)
        )
      }
    yield ()

object StepUpClient:

  final case class State(
      requestedScopes: Set[String] = Set.empty,
      grantedScopes: Set[String] = Set.empty,
      currentToken: Option[String] = None
  )

  final case class NarrowerGrantException(
      operation: String,
      withheldScopes: Set[String],
      grantedScopes: Set[String]
  ) extends Exception(
        s"Authorization server withheld scopes [${withheldScopes.mkString(" ")}] required for '$operation'. " +
          s"Granted only [${grantedScopes.mkString(" ")}]. Narrower grants cannot be retried automatically."
      )

  private val ScopePattern: Regex = """scope="([^"]*)"""".r

  /** Parses scopes from `WWW-Authenticate: Bearer error="insufficient_scope", ..., scope="..."` headers. */
  def parseChallengeScopes(headers: List[String]): Set[String] =
    headers.flatMap { h =>
      if h.contains("error=\"insufficient_scope\"") || h.contains("insufficient_scope") then
        ScopePattern.findFirstMatchIn(h).map(_.group(1).split("\\s+").filter(_.nonEmpty).toSet).getOrElse(Set.empty)
      else Set.empty
    }.toSet

  /** Creates a StepUpClient with the Scope-Union Rule. */
  def create[F[_]: Concurrent](
      authorize: Set[String] => F[TokenResponse],
      strategy: ScopeStrategy = ScopeStrategy.Union,
      maxRetries: Int = 2
  ): F[StepUpClient[F]] =
    Ref.of[F, State](State()).map(ref => new StepUpClient[F](ref, authorize, strategy, maxRetries))
