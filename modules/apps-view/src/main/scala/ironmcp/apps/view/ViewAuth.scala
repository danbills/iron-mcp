package ironmcp.apps.view

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.*
import ironmcp.protocol.{CallToolResult, ContentBlock, ErrorCode}

/** Who is signed in, as the server's `whoami` tool reports it (iron-mcp-auth's
  * `SessionTools`). A View never sees a token: the host holds it, as the MCP
  * Apps spec intends. What the View can know is what the server tells it.
  */
final case class ViewSession(
    signedIn: Boolean,
    subject: Option[String] = None,
    username: Option[String] = None,
    scopes: List[String] = Nil,
    unlocked: List[String] = Nil,
    locked: List[ViewSession.Locked] = Nil,
    expiresAt: Option[Long] = None,
    keyBound: Boolean = false
):
  /** Why `tool` cannot be called yet, if it cannot. */
  def lock(tool: String): Option[ViewSession.Locked] = locked.find(_.name == tool)

  def canCall(tool: String): Boolean = unlocked.contains(tool)

object ViewSession:
  /** A tool the caller may not call yet, the scopes it needs, and the tool
    * that asks the host for them without doing anything else.
    */
  final case class Locked(name: String, needs: List[String], unlockWith: Option[String] = None) derives Decoder

  given Decoder[ViewSession] = Decoder.instance { c =>
    for
      signedIn  <- c.getOrElse[Boolean]("signedIn")(false)
      subject   <- c.get[Option[String]]("subject")
      username  <- c.get[Option[String]]("username")
      scopes    <- c.getOrElse[List[String]]("scopes")(Nil)
      unlocked  <- c.getOrElse[List[String]]("unlocked")(Nil)
      locked    <- c.getOrElse[List[Locked]]("locked")(Nil)
      expiresAt <- c.get[Option[Long]]("expiresAt")
      keyBound  <- c.getOrElse[Boolean]("keyBound")(false)
    yield ViewSession(signedIn, subject, username, scopes, unlocked, locked, expiresAt, keyBound)
  }

/** Authorization as a View sees it.
  *
  * A protected server tool called from a View goes View → host → server. If
  * the server answers `401` or `403 insufficient_scope`, an Apps host runs the
  * sign-in or step-up itself and retries; the View just sees the call take
  * longer, then succeed. When the host cannot (the user declined, the host
  * does not do OAuth), the View gets an error, and [[ViewAuth.classify]] says
  * which kind.
  *
  * To show a lock before anything fails, ask the server: [[session]] calls
  * `whoami`, and [[unlock]] calls the `unlock_*` tool a locked tool names,
  * which needs exactly the missing scopes and does nothing else, so the
  * host's step-up runs without the real action running with it.
  */
object ViewAuth:

  enum Outcome:
    /** Not signed in, and the host did not sign in. */
    case SignInRequired

    /** Signed in, without these scopes. */
    case MoreAccessRequired(scopes: Set[String])

    /** Refused outright: the policy does not let this caller use it at all. */
    case Forbidden(message: String)

    /** Not about authorization. */
    case Failed(error: Throwable)

  /** The session, from the server's `whoami` tool. */
  def session[F[_]: MonadThrow](view: McpAppView[F], whoami: String = "whoami"): F[ViewSession] =
    view.callServerTool(whoami).flatMap { result =>
      result.structuredContent.toRight(new IllegalStateException(s"$whoami returned no structured content")).flatMap(_.as[ViewSession]).liftTo[F]
    }

  /** Asks the host for what `locked` needs, through its `unlockWith` tool,
    * then returns the new session; or why it could not.
    */
  def unlock[F[_]: MonadThrow](view: McpAppView[F], locked: ViewSession.Locked, whoami: String = "whoami"): F[Either[Outcome, ViewSession]] =
    locked.unlockWith match
      case None => (Outcome.Forbidden(s"nothing unlocks ${locked.name}"): Outcome).asLeft[ViewSession].pure[F]
      case Some(tool) =>
        view.callServerTool(tool).attempt.flatMap {
          case Left(error) => classify(error).asLeft[ViewSession].pure[F]
          case Right(r) =>
            classify(r) match
              case Some(outcome) => outcome.asLeft[ViewSession].pure[F]
              case None          => session(view, whoami).map(_.asRight[Outcome])
        }

  /** What an error from a server call means, as far as one can tell: hosts
    * relay a refused call in their own words, so this reads the JSON-RPC
    * error's code, data and message for the OAuth vocabulary (RFC 6750).
    */
  def classify(error: Throwable): Outcome = error match
    case McpAppViewError(_, rpc) =>
      val text = (rpc.message: String) + " " + rpc.data.fold("")(_.noSpaces)
      val lower = text.toLowerCase
      if lower.contains("insufficient_scope") || lower.contains("403") then Outcome.MoreAccessRequired(scopesIn(text))
      else if rpc.code == ErrorCode.UrlElicitationRequired || lower.contains("invalid_token") || lower.contains("401") || lower.contains("unauthorized") then
        Outcome.SignInRequired
      else if lower.contains("forbidden") || lower.contains("not permitted") then Outcome.Forbidden(rpc.message)
      else Outcome.Failed(error)
    case other => Outcome.Failed(other)

  /** A tool result the server's second check refused (`ScopedTools`). */
  def classify(result: CallToolResult): Option[Outcome] =
    if !result.isError.contains(true) then None
    else
      val text = result.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString(" ")
      Option.when(text.contains("is not permitted"))(Outcome.Forbidden(text))

  private val ScopeParam = "scope=\\\\?\"([^\"\\\\]*)".r

  private def scopesIn(text: String): Set[String] =
    ScopeParam.findFirstMatchIn(text).fold(Set.empty[String])(_.group(1).split(' ').filter(_.nonEmpty).toSet)
