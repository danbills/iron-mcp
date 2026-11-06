package ironmcp
package auth

import cats.effect.{Async, Ref, Sync}
import cats.effect.std.SecureRandom
import cats.syntax.all.*
import io.circe.*
import io.circe.parser.parse as parseJson
import io.circe.syntax.*
import ironmcp.protocol.ElicitAction
import ironmcp.server.{Elicit, Interaction, ToolContext}
import org.http4s.*
import org.http4s.headers.{Location, `Cache-Control`, `Content-Type`}
import org.typelevel.ci.CIString

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.{Cipher, Mac, SecretKey}
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.concurrent.duration.*

/** A user's token for a third-party API, as kept by the MCP server. */
final case class StoredToken(accessToken: String, refreshToken: Option[String], expiresAt: Option[Long], scopes: Set[String]) derives Codec.AsObject:
  override def toString = s"StoredToken(***, ${refreshToken.fold("no refresh")(_ => "***")}, $expiresAt, $scopes)"

/** Who a stored token belongs to: an MCP user, by issuer and subject. */
final case class Owner(issuer: String, subject: String):
  def key: String = s"$issuer $subject"

object Owner:
  def of(claims: TokenClaims): Option[Owner] = claims.sub.map(Owner(claims.iss, _))

/** Where the server keeps users' third-party tokens. */
trait TokenVault[F[_]]:
  def get(owner: Owner, provider: String): F[Option[StoredToken]]
  def put(owner: Owner, provider: String, token: StoredToken): F[Unit]
  def remove(owner: Owner, provider: String): F[Unit]

/** Raw bytes by key: a database table, a file, Valkey. [[TokenVault.encrypted]]
  * stores only ciphertext in it.
  */
trait SecretStore[F[_]]:
  def get(key: String): F[Option[Array[Byte]]]
  def put(key: String, value: Array[Byte]): F[Unit]
  def remove(key: String): F[Unit]

object SecretStore:
  def inMemory[F[_]: Sync]: F[SecretStore[F]] =
    Ref.of[F, Map[String, Array[Byte]]](Map.empty).map { ref =>
      new SecretStore[F]:
        def get(key: String)                     = ref.get.map(_.get(key))
        def put(key: String, value: Array[Byte]) = ref.update(_.updated(key, value))
        def remove(key: String)                  = ref.update(_ - key)
    }

object TokenVault:

  /** AES-256-GCM over `store`. Each entry is encrypted with a fresh nonce,
    * and its owner and provider are the associated data, so a ciphertext
    * moved to another user's row fails to decrypt rather than handing one
    * user's token to another. `key` must be 32 bytes, from a KMS or a
    * secrets manager, never from the repository.
    */
  def encrypted[F[_]: Sync](store: SecretStore[F], key: Array[Byte]): Either[String, TokenVault[F]] =
    Either.cond(key.length == 32, new Encrypted(store, new SecretKeySpec(key, "AES")), "the vault key must be 32 bytes (AES-256)")

  /** An encrypted vault in memory, for one process and for tests. */
  def inMemory[F[_]: Sync](key: Array[Byte]): F[TokenVault[F]] =
    SecretStore.inMemory[F].flatMap(store => encrypted(store, key).leftMap(new IllegalArgumentException(_)).liftTo[F])

  private final class Encrypted[F[_]](store: SecretStore[F], key: SecretKey)(using F: Sync[F]) extends TokenVault[F]:
    private val random = new java.security.SecureRandom()

    private def slot(owner: Owner, provider: String) = s"${owner.key}\u0000$provider"

    def get(owner: Owner, provider: String): F[Option[StoredToken]] =
      store.get(slot(owner, provider)).flatMap(_.traverse(bytes => F.delay(open(bytes, slot(owner, provider))).flatMap(_.liftTo[F])))

    def put(owner: Owner, provider: String, token: StoredToken): F[Unit] =
      F.delay(seal(token.asJson.noSpaces.getBytes(UTF_8), slot(owner, provider))).flatMap(store.put(slot(owner, provider), _))

    def remove(owner: Owner, provider: String): F[Unit] = store.remove(slot(owner, provider))

    private def seal(plain: Array[Byte], aad: String): Array[Byte] =
      val nonce = new Array[Byte](12)
      random.nextBytes(nonce)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce))
      cipher.updateAAD(aad.getBytes(UTF_8))
      ByteBuffer.allocate(1 + 12 + plain.length + 16).put(1.toByte).put(nonce).put(cipher.doFinal(plain)).array()

    private def open(sealedBytes: Array[Byte], aad: String): Either[Throwable, StoredToken] =
      Either.catchNonFatal {
        if sealedBytes.length < 1 + 12 + 16 || sealedBytes(0) != 1 then throw new IllegalStateException("not a vault entry")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealedBytes.slice(1, 13)))
        cipher.updateAAD(aad.getBytes(UTF_8))
        new String(cipher.doFinal(sealedBytes.drop(13)), UTF_8)
      }.flatMap(text => parseJson(text).flatMap(_.as[StoredToken]))

/** A third-party API the MCP server calls on its users' behalf.
  *
  * @param name        a short name, used in paths and the vault
  * @param server      its authorization server
  * @param client      the MCP server's own registration there
  * @param callbackUrl the public URL of this server's callback route
  *                    (`.../oauth/downstream/<name>/callback`), registered with it
  * @param scopes      what to ask for
  * @param resource    its RFC 8707 resource, if it takes one
  */
final case class Downstream(
    name: String,
    server: AuthorizationServerMetadata,
    client: ClientAuth,
    callbackUrl: String,
    scopes: Set[String],
    resource: Option[String] = None
)

/** Third-party API tokens without token passthrough.
  *
  * The MCP spec forbids forwarding the MCP access token to another API (the
  * "confused deputy"): the other API would trust a token never meant for it.
  * Instead, the MCP server holds its own token for each user and API, got
  * through the user's browser:
  *
  *   1. A tool calls [[withToken]]. With a token in the vault, the tool runs.
  *   2. Without one, the tool answers with a URL elicitation: the client
  *      shows the user a link to this server's `start` route, carrying a
  *      ticket that is signed, bound to the user, single-use and short-lived.
  *   3. `start` sends the browser to the API's authorization server, with
  *      PKCE and a `state` kept on this server.
  *   4. The `callback` route checks `state` (and RFC 9207 `iss`), exchanges
  *      the code with this server's own client credentials, and stores the
  *      token in the vault, encrypted, under the user who was asked.
  *   5. The client retries the tool, which now finds the token.
  *
  * **Who opens the link.** A link can be forwarded: whoever opens it signs
  * in to the API, and their account is stored for the user the ticket names.
  * Tickets are single-use and expire in five minutes, which narrows this.
  * To close it, pass `browserUser`: it reads who is signed in in the
  * browser (a session cookie from this server's own sign-in) and `start`
  * refuses anyone but the ticket's user.
  */
final class DownstreamAuth[F[_]] private (
    http: org.http4s.client.Client[F],
    api: Downstream,
    vault: TokenVault[F],
    ticketKey: SecretKey,
    random: SecureRandom[F],
    pending: Ref[F, Map[String, DownstreamAuth.Pending]],
    used: ReplayCache[F],
    startUrl: String,
    browserUser: Option[Request[F] => F[Option[Owner]]],
    ticketLifetime: FiniteDuration
)(using F: Async[F]):
  import DownstreamAuth.*

  /** Runs `use` with the caller's token for this API, or asks the caller to
    * sign in to it first.
    */
  def withToken[O](caller: TokenClaims, ctx: ToolContext)(use: String => F[Interaction[O]]): F[Interaction[O]] =
    Owner.of(caller) match
      case None => F.raiseError(new IllegalStateException("a token without a subject cannot own third-party tokens"))
      case Some(owner) =>
        current(owner).flatMap {
          case Some(token) => use(token.accessToken)
          case None =>
            val declined = ctx.response(SignInKey).exists(r => r.action != ElicitAction.Accept)
            if declined then F.raiseError(new IllegalStateException(s"sign-in to ${api.name} was declined"))
            else
              ticket(owner).map { (id, url) =>
                ctx.ask(s"$StatePrefix${api.name}")(SignInKey -> Elicit.url(s"Sign in to ${api.name} so this tool can act for you there.", url, id))
              }
        }

  /** The caller's token, refreshed first if it is about to expire; `None` if
    * there is none (or it can no longer be refreshed).
    */
  def current(owner: Owner): F[Option[StoredToken]] =
    F.realTime.map(_.toSeconds).flatMap { now =>
      vault.get(owner, api.name).flatMap {
        case Some(t) if t.expiresAt.forall(_ > now + 30) => F.pure(Some(t))
        case Some(t) =>
          t.refreshToken match
            case None => vault.remove(owner, api.name).as(None)
            case Some(rt) =>
              OAuthEndpoint
                .post[F, TokenResponse](http, api.server.issuer, api.server.token_endpoint, api.client, List("grant_type" -> "refresh_token", "refresh_token" -> rt) ++ api.resource.map("resource" -> _))
                .flatMap(r => store(owner, r, now, Some(rt)).map(Some(_)))
                .handleErrorWith(_ => vault.remove(owner, api.name).as(None))
        case None => F.pure(None)
      }
    }

  /** Forgets the caller's token for this API. */
  def disconnect(owner: Owner): F[Unit] = vault.remove(owner, api.name)

  /** `GET <start>?ticket=…` and `GET <callback>?code=…&state=…`. Mount them
    * at the paths of `startUrl` and `api.callbackUrl`.
    */
  def routes: HttpRoutes[F] =
    val startPath    = Uri.unsafeFromString(startUrl).path.renderString
    val callbackPath = Uri.unsafeFromString(api.callbackUrl).path.renderString
    HttpRoutes.of[F] {
      case req if req.method == Method.GET && req.uri.path.renderString == startPath    => start(req)
      case req if req.method == Method.GET && req.uri.path.renderString == callbackPath => callback(req)
    }

  private def ticket(owner: Owner): F[(String, String)] =
    for
      now <- F.realTime.map(_.toSeconds)
      id  <- random.nextBytes(16).map(b64)
      body = Json.obj("o" -> owner.issuer.asJson, "s" -> owner.subject.asJson, "p" -> api.name.asJson, "i" -> id.asJson, "x" -> (now + ticketLifetime.toSeconds).asJson).noSpaces
      payload = b64(body.getBytes(UTF_8))
    yield (id, s"$startUrl?ticket=$payload.${b64(mac(payload))}")

  private def mac(payload: String): Array[Byte] =
    val m = Mac.getInstance("HmacSHA256")
    m.init(ticketKey)
    m.doFinal(s"downstream-ticket.v1.$payload".getBytes(UTF_8))

  private def readTicket(raw: String, now: Long): Option[(Owner, String, Long)] =
    raw.split('.') match
      case Array(payload, sig) if scala.util.Try(Base64.getUrlDecoder.decode(sig)).toOption.exists(s => MessageDigest.isEqual(s, mac(payload))) =>
        scala.util.Try(new String(Base64.getUrlDecoder.decode(payload), UTF_8)).toOption.flatMap(parseJson(_).toOption).flatMap { j =>
          val c = j.hcursor
          (c.get[String]("o"), c.get[String]("s"), c.get[String]("p"), c.get[String]("i"), c.get[Long]("x")).mapN { (o, s, p, i, x) =>
            Option.when(p == api.name && x >= now)((Owner(o, s), i, x))
          }.toOption.flatten
        }
      case _ => None

  private def start(req: Request[F]): F[Response[F]] =
    F.realTime.map(_.toSeconds).flatMap { now =>
      req.params.get("ticket").flatMap(readTicket(_, now)) match
        case None => page(Status.BadRequest, "This sign-in link is invalid or has expired. Run the tool again for a new one.")
        case Some((owner, id, until)) =>
          browserUser.traverse(_(req)).flatMap { inBrowser =>
            if inBrowser.exists(_ != Some(owner)) then page(Status.Forbidden, "This sign-in link belongs to someone else.")
            else
              used.firstUse(s"ticket:$id", until).flatMap {
                case false => page(Status.BadRequest, "This sign-in link has already been used. Run the tool again for a new one.")
                case true =>
                  for
                    pkce  <- Pkce.generate(random)
                    state <- random.nextBytes(24).map(b64)
                    _     <- pending.update(m => m.filter(_._2.until >= now).updated(state, Pending(owner, pkce.verifier, now + 600)))
                    authorize = Uri
                      .unsafeFromString(api.server.authorization_endpoint.getOrElse(s"${api.server.issuer.stripSuffix("/")}/authorize"))
                      .withQueryParams(
                        Map(
                          "response_type"         -> "code",
                          "client_id"             -> api.client.clientId,
                          "redirect_uri"          -> api.callbackUrl,
                          "code_challenge"        -> pkce.challenge,
                          "code_challenge_method" -> "S256",
                          "state"                 -> state,
                          "scope"                 -> api.scopes.toList.sorted.mkString(" ")
                        ) ++ api.resource.map("resource" -> _)
                      )
                  yield hardened(Response[F](Status.Found).putHeaders(Location(authorize)))
              }
          }
    }

  private def callback(req: Request[F]): F[Response[F]] =
    val state = req.params.getOrElse("state", "")
    for
      now   <- F.realTime.map(_.toSeconds)
      found <- pending.modify(m => (m - state, m.get(state).filter(_.until >= now)))
      reply <- found match
        case None => page(Status.BadRequest, "This sign-in has expired or was already completed.")
        case Some(p) =>
          val issOk = req.params.get("iss") match
            case Some(iss) => iss.stripSuffix("/") == api.server.issuer.stripSuffix("/")
            case None      => !api.server.authorization_response_iss_parameter_supported.contains(true)
          (req.params.get("code"), issOk) match
            case (_, false)   => page(Status.BadRequest, "The sign-in came back from an unexpected server.")
            case (None, true) => page(Status.BadRequest, s"Sign-in to ${api.name} was not completed.")
            case (Some(code), true) =>
              OAuthEndpoint
                .post[F, TokenResponse](
                  http,
                  api.server.issuer,
                  api.server.token_endpoint,
                  api.client,
                  List("grant_type" -> "authorization_code", "code" -> code, "redirect_uri" -> api.callbackUrl, "code_verifier" -> p.verifier) ++ api.resource.map("resource" -> _)
                )
                .flatMap(r => store(p.owner, r, now, None))
                .attempt
                .flatMap {
                  case Right(_) => page(Status.Ok, s"You are signed in to ${api.name}. You can close this window and go back to your conversation.")
                  case Left(_)  => page(Status.BadGateway, s"${api.name} did not accept the sign-in. Try again.")
                }
    yield reply

  private def store(owner: Owner, r: TokenResponse, now: Long, previousRefresh: Option[String]): F[StoredToken] =
    val token = StoredToken(r.access_token, r.refresh_token.orElse(previousRefresh), r.expires_in.map(now + _), r.grantedScopes)
    vault.put(owner, api.name, token).as(token)

  private def page(status: Status, message: String): F[Response[F]] =
    val escaped = message.flatMap {
      case '<' => "&lt;"; case '>' => "&gt;"; case '&' => "&amp;"; case '"' => "&quot;"; case c => c.toString
    }
    F.pure(
      hardened(
        Response[F](status)
          .withEntity(s"""<!doctype html><meta charset="utf-8"><title>${api.name}</title><p>$escaped</p>""")
          .withContentType(`Content-Type`(MediaType.text.html, Charset.`UTF-8`))
      )
    )

  /** Nothing on these pages may be framed, cached, scripted or leak its URL. */
  private def hardened(r: Response[F]): Response[F] =
    r.putHeaders(
      `Cache-Control`(CacheDirective.`no-store`),
      Header.Raw(CIString("Content-Security-Policy"), "default-src 'none'; frame-ancestors 'none'"),
      Header.Raw(CIString("Referrer-Policy"), "no-referrer"),
      Header.Raw(CIString("X-Content-Type-Options"), "nosniff")
    )

object DownstreamAuth:
  val SignInKey   = "downstream-sign-in"
  val StatePrefix = "downstream:"

  private[auth] final case class Pending(owner: Owner, verifier: String, until: Long)

  private def b64(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** @param ticketKey   at least 32 bytes, kept secret: it signs the sign-in links
    * @param startUrl    the public URL of the `start` route
    * @param browserUser who is signed in in the browser, to refuse a
    *                    forwarded link (see the class documentation)
    */
  def apply[F[_]: Async](
      http: org.http4s.client.Client[F],
      api: Downstream,
      vault: TokenVault[F],
      ticketKey: Array[Byte],
      startUrl: String,
      browserUser: Option[Request[F] => F[Option[Owner]]] = None,
      ticketLifetime: FiniteDuration = 5.minutes
  ): F[DownstreamAuth[F]] =
    for
      _       <- Async[F].raiseWhen(ticketKey.length < 32)(new IllegalArgumentException("the ticket key must be at least 32 bytes"))
      _       <- (List(startUrl, api.callbackUrl, api.server.token_endpoint) ++ api.server.authorization_endpoint).traverse_(u =>
                   ResourceIdentity.requireSecure(u).leftMap(OAuthError.Insecure(_)).liftTo[F]
                 )
      _       <- Async[F].raiseWhen(!api.server.code_challenge_methods_supported.exists(_.contains("S256")))(OAuthError.PkceUnsupported(api.server.issuer))
      random  <- SecureRandom.javaSecuritySecureRandom[F]
      pending <- Ref.of[F, Map[String, Pending]](Map.empty)
      used    <- ReplayCache.inMemory[F]()
    yield new DownstreamAuth(http, api, vault, new SecretKeySpec(ticketKey, "HmacSHA256"), random, pending, used, startUrl, browserUser, ticketLifetime)
