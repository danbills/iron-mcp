package ironmcp
package auth

import cats.ApplicativeThrow
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.ToolNameC
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.McpTool

/** Ready-made tools about the caller's own session, for a server and its
  * Views: a View can show who is signed in and what is unlocked without ever
  * seeing a token (the MCP Apps spec keeps tokens with the host, and so does
  * this).
  */
object SessionTools:

  final case class NoArguments(@annotation.unused reserved: Option[String] = None) derives Decoder, JsonSchemaOf

  /** What `whoami` tells the caller about itself. Only what the caller already
    * knows or may know: no token, no signature, no other claims.
    *
    * @param signedIn  whether the request carried a valid token
    * @param subject   the token's `sub`
    * @param username  `preferred_username`, when the issuer sends it
    * @param scopes    the scopes granted
    * @param unlocked  the tools this caller may call
    * @param locked    the tools it may not, each with the scopes it would need
    * @param expiresAt when the token expires, epoch seconds
    * @param keyBound  whether the token is DPoP-bound to the client's key
    */
  final case class Session(
      signedIn: Boolean,
      subject: Option[String],
      username: Option[String],
      scopes: List[String],
      unlocked: List[String],
      locked: List[LockedTool],
      expiresAt: Option[Long],
      keyBound: Boolean
  ) derives Encoder.AsObject, JsonSchemaOf

  /** A tool the caller may not call yet, the scopes it needs, and the
    * [[unlock]] tool that asks for them.
    */
  final case class LockedTool(name: String, needs: List[String], unlockWith: Option[String]) derives Encoder.AsObject, JsonSchemaOf

  final case class SignedOut(signedOut: Boolean, session: Option[String]) derives Encoder.AsObject, JsonSchemaOf

  /** The session of `caller` against `policy`'s tools. */
  def session(caller: Option[TokenClaims], policy: AuthPolicy): Session =
    val unlockers = policy.tools.toList.filter((name, _) => name.startsWith(UnlockPrefix)).sortBy((_, a) => a.required.size)
    val (open, shut) = policy.tools.toList.filterNot(_._1.startsWith(UnlockPrefix)).sortBy(_._1).partition((name, _) => policy.allows(Target.Tool(name), caller))
    Session(
      signedIn = caller.isDefined,
      subject = caller.flatMap(_.sub),
      username = caller.flatMap(_.preferredUsername),
      scopes = caller.fold(Nil)(_.scopes.toList.sorted),
      unlocked = open.map(_._1),
      locked = shut.map { (name, access) =>
        val needs = access.required
        LockedTool(name, needs.toList.sorted, unlockers.collectFirst { case (u, Access.Scopes(s)) if needs.subsetOf(s) && needs.nonEmpty => u })
      },
      expiresAt = caller.map(_.exp),
      keyBound = caller.exists(_.boundKeyThumbprint.isDefined)
    )

  /** `whoami`: the caller's [[Session]]. Public, so an anonymous caller gets
    * `signedIn: false` and the list of what signing in unlocks.
    */
  def whoami[F[_]: ApplicativeThrow](caller: Option[TokenClaims], policy: AuthPolicy): McpTool[F] =
    McpTool[NoArguments]("whoami", "Who is signed in, which scopes they hold, and which tools those unlock.")(_ =>
      session(caller, policy).pure[F]
    ).public

  /** Tools whose names start with this are unlockers, not shown as tools to unlock. */
  val UnlockPrefix = "unlock_"

  /** A tool that needs exactly `scopes` and does nothing but report the new
    * session. A View calls it to make the host sign in or step up (its `403`
    * names the scopes) without running the action the scopes are for. Name it
    * `unlock_<something>`; `whoami` points locked tools at it. With MCP Apps,
    * keep it from the model: `.withVisibility(Visibility.App)`.
    */
  def unlock[F[_]: ApplicativeThrow](name: String, caller: Option[TokenClaims], policy: AuthPolicy)(scope: String, more: String*): McpTool[F] =
    require(name.startsWith(UnlockPrefix), s"an unlock tool's name starts with $UnlockPrefix")
    val scopes = (scope +: more).toList.sorted
    val toolName    = name.refineEither[ToolNameC].fold(e => throw new IllegalArgumentException(s"'$name': $e"), identity)
    val description = s"Asks to be granted ${scopes.mkString(", ")}. Does nothing else.".assume[Not[Empty]]
    McpTool[NoArguments](toolName, description)(_ =>
      session(caller, policy).pure[F]
    ).requires(scope, more*)

  /** Ends the caller's session on this server: its token (and every token of
    * its login session, by `sid`) is refused from now on, here. To end it at
    * the identity provider too, the client signs out there.
    */
  def signOut[F[_]: ApplicativeThrow](caller: TokenClaims, revocations: RevocationList[F]): McpTool[F] =
    McpTool[NoArguments]("sign_out", "Sign out of this server: the current token stops working here.")(_ =>
      revocations.revoke(caller).as(SignedOut(signedOut = true, session = caller.sid))
    ).signedIn

  /** Adds the session tools' own rules to `policy`. */
  def declare(policy: AuthPolicy): AuthPolicy =
    policy.tool("whoami", Access.Public).tool("sign_out", Access.SignedIn)
