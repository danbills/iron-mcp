package ironmcp
package auth

import cats.Monad
import cats.effect.{Async, Ref, Sync}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import org.http4s.{Header, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.net.URI
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, MessageDigest, SecureRandom}
import java.util.Base64
import scala.concurrent.duration.*

/** Whether this resource server takes DPoP-bound tokens (RFC 9449).
  *
  * A token bound to a key (`cnf.jkt`) is useless without that key's private
  * half: each request carries a fresh proof, signed with the key, naming the
  * method, the URL and a hash of the token. A token copied from a log, a proxy
  * or a compromised client cannot be replayed by anyone else.
  */
enum DpopPolicy:
  /** Bearer tokens are accepted; a token bound to a key must come with a
    * valid proof from that key, and is refused as a plain Bearer token.
    */
  case Allowed

  /** Only DPoP-bound tokens, each with a valid proof. */
  case Required

/** Remembers the proofs already used, so each is good once. */
trait ReplayCache[F[_]]:
  /** `true` the first time `key` is seen before `until`, `false` after. */
  def firstUse(key: String, until: Long): F[Boolean]

object ReplayCache:
  /** In one process. Several replicas need a shared store (Valkey, with
    * `SET key 1 NX EXAT until`).
    */
  def inMemory[F[_]: Sync](maxEntries: Int = 100000): F[ReplayCache[F]] =
    Ref.of[F, Map[String, Long]](Map.empty).map(fromRef(_, maxEntries))

  /** For constructors that cannot run an effect: the state is allocated
    * eagerly, once, as `Ref.unsafe` does.
    */
  def unsafeInMemory[F[_]: Sync](maxEntries: Int = 100000): ReplayCache[F] =
    fromRef(Ref.unsafe[F, Map[String, Long]](Map.empty), maxEntries)

  private def fromRef[F[_]: Sync](ref: Ref[F, Map[String, Long]], maxEntries: Int): ReplayCache[F] =
    (key, until) =>
      Sync[F].realTime.flatMap { now =>
        ref.modify { seen =>
          if seen.get(key).exists(_ >= now.toSeconds) then (seen, false)
          else
            val live = if seen.size >= maxEntries then seen.filter(_._2 >= now.toSeconds) else seen
            // Full of live entries: refuse rather than forget (fail closed).
            if live.size >= maxEntries then (live, false) else (live.updated(key, until), true)
        }
      }

/** RFC 9449 Demonstrating Proof of Possession, both sides. */
object Dpop:

  val HeaderName: CIString = CIString("DPoP")
  val NonceHeader: CIString = CIString("DPoP-Nonce")
  val ProofType = "dpop+jwt"

  /** How proofs are checked at the resource server. */
  final case class Settings(
      algorithms: Set[JwsAlgorithm] = Set(JwsAlgorithm.ES256, JwsAlgorithm.EdDSA, JwsAlgorithm.PS256, JwsAlgorithm.RS256),
      /** How old a proof may be; also how long its `jti` is remembered. */
      window: FiniteDuration = 2.minutes,
      clockSkew: FiniteDuration = 30.seconds,
      maxProofBytes: Int = 4096
  )

  /** What a checked proof says. */
  final case class Proof(thumbprint: String, jti: String, iat: Long)

  /** `BASE64URL(SHA-256(token))`: the `ath` a proof carries for `token`. */
  def tokenHash(token: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(US_ASCII)))

  /** `htu` as RFC 9449 §4.3 compares it: scheme and host lowercased, default
    * port dropped, no query or fragment.
    */
  def normalizeHtu(url: String): Option[String] =
    scala.util.Try(new URI(url)).toOption.filter(u => u.getScheme != null && u.getHost != null).map { u =>
      val scheme = u.getScheme.toLowerCase
      val port = (scheme, u.getPort) match
        case (_, -1) | ("https", 443) | ("http", 80) => ""
        case (_, p)                                   => s":$p"
      val path = Option(u.getRawPath).filter(_.nonEmpty).getOrElse("/")
      s"$scheme://${u.getHost.toLowerCase}$port$path"
    }

  /** Checks a proof's form, signature and claims (RFC 9449 §4.3, steps 1-11
    * but replay, which needs state: see [[verify]]).
    *
    * @param htm         the request's method
    * @param htu         the URL the client was sending to (the resource's public URL)
    * @param accessToken the token it accompanies, for `ath`
    */
  def check(proof: String, htm: String, htu: String, accessToken: Option[String], now: Long, settings: Settings): Either[AuthError, Proof] =
    def bad(reason: String) = AuthError.InvalidDpopProof(reason)
    for
      parts <- Jwt.parse(proof, settings.maxProofBytes).leftMap(e => bad(e.description))
      _     <- Either.cond(parts.typ.contains(ProofType), (), bad(s"typ is not $ProofType"))
      alg   <- Jwt.algorithm(parts, settings.algorithms.filter(_.isAsymmetric)).leftMap(_ => bad(s"algorithm '${parts.algorithm}' is not accepted"))
      jwk   <- parts.header("jwk").toRight(bad("no jwk")).flatMap(_.as[Jwk].leftMap(_ => bad("the jwk is malformed")))
      _     <- Either.cond(jwk.d.isEmpty, (), bad("the jwk holds a private key"))
      key   <- jwk.copy(alg = None, use = None, key_ops = None, kid = None).toVerificationKey.leftMap(bad)
      _     <- Either.cond(key.fits(alg), (), bad("the jwk does not fit the algorithm"))
      _     <- Jwt.verify(parts, key.key, Set(alg)).leftMap(_ => bad("the signature does not verify"))
      thumb <- jwk.thumbprint.leftMap(bad)
      c      = parts.payload.hcursor
      jti   <- c.get[String]("jti").leftMap(_ => bad("no jti")).filterOrElse(j => j.nonEmpty && j.length <= 256, bad("jti is empty or too long"))
      m     <- c.get[String]("htm").leftMap(_ => bad("no htm"))
      _     <- Either.cond(m == htm, (), bad(s"htm '$m' is not '$htm'"))
      u     <- c.get[String]("htu").leftMap(_ => bad("no htu"))
      _     <- Either.cond(normalizeHtu(u).isDefined && normalizeHtu(u) == normalizeHtu(htu), (), bad("htu does not name this resource"))
      iat   <- c.get[Long]("iat").leftMap(_ => bad("no iat"))
      _     <- Either.cond(iat <= now + settings.clockSkew.toSeconds, (), bad("iat is in the future"))
      _     <- Either.cond(iat >= now - settings.window.toSeconds, (), bad("the proof is too old"))
      _ <- accessToken.traverse_ { token =>
        c.get[String]("ath").leftMap(_ => bad("no ath")).flatMap { ath =>
          Either.cond(MessageDigest.isEqual(ath.getBytes(UTF_8), tokenHash(token).getBytes(UTF_8)), (), bad("ath does not match the token"))
        }
      }
    yield Proof(thumb, jti, iat)

  /** [[check]], then refuses a proof already used. */
  def verify[F[_]: Monad](
      proof: String,
      htm: String,
      htu: String,
      accessToken: Option[String],
      now: Long,
      settings: Settings,
      replay: ReplayCache[F]
  ): F[Either[AuthError, Proof]] =
    check(proof, htm, htu, accessToken, now, settings) match
      case Left(e) => e.asLeft[Proof].pure[F]
      case Right(p) =>
        replay
          .firstUse(s"${p.thumbprint}:${p.jti}", p.iat + settings.window.toSeconds + settings.clockSkew.toSeconds)
          .map(first => Either.cond(first, p, AuthError.InvalidDpopProof("the proof was already used")))

  /** A client's DPoP key: it signs a proof for each request.
    *
    * Keep one per client installation for the life of its tokens: the tokens
    * are bound to it, and a new key means new tokens.
    */
  final class Key private (pair: KeyPair, val algorithm: JwsAlgorithm):
    val jwk: Jwk = Jwk.fromPublicKey(pair.getPublic).fold(e => throw new IllegalStateException(e), identity)

    /** RFC 7638 thumbprint: what the authorization server puts in `cnf.jkt`. */
    val thumbprint: String = jwk.thumbprint.fold(e => throw new IllegalStateException(e), identity)

    /** A fresh proof for one request to `url` with `method`, carrying the
      * hash of `accessToken` (to a resource server) and the server's `nonce`
      * if it asked for one.
      */
    def proof[F[_]: Sync](method: String, url: String, accessToken: Option[String] = None, nonce: Option[String] = None): F[String] =
      Sync[F].realTime.flatMap { now =>
        Sync[F].delay {
          val jti = new Array[Byte](16)
          Key.random.nextBytes(jti)
          val claims = JsonObject.fromIterable(
            List(
              "jti" -> Base64.getUrlEncoder.withoutPadding.encodeToString(jti).asJson,
              "htm" -> method.asJson,
              "htu" -> normalizeHtu(url).getOrElse(url).asJson,
              "iat" -> now.toSeconds.asJson
            ) ++ accessToken.map(t => "ath" -> tokenHash(t).asJson) ++ nonce.map(n => "nonce" -> n.asJson)
          )
          Jwt.sign(algorithm, pair.getPrivate, JsonObject("typ" -> ProofType.asJson, "jwk" -> jwk.asJson), Json.fromJsonObject(claims))
        }
      }

  object Key:
    private val random = new SecureRandom()

    /** A new P-256 key (ES256). */
    def generate[F[_]: Sync]: F[Key] =
      Sync[F].delay {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(new ECGenParameterSpec("secp256r1"))
        new Key(g.generateKeyPair(), JwsAlgorithm.ES256)
      }

    /** An existing key pair (from a keystore), with its algorithm. */
    def of(pair: KeyPair, algorithm: JwsAlgorithm): Either[String, Key] =
      Jws.keyFits(algorithm, pair.getPublic).as(new Key(pair, algorithm))

  /** Client middleware: every request that carries `Authorization: DPoP
    * <token>` gets a fresh proof for its method and URL. If the server
    * answers `use_dpop_nonce` with a `DPoP-Nonce`, the request is sent once
    * more with that nonce, and the nonce is kept for later requests.
    */
  def client[F[_]: Async](underlying: Client[F], key: Key): F[Client[F]] =
    Ref.of[F, Option[String]](None).map { nonce =>
      Client[F] { request =>
        val token = request.headers.get(CIString("Authorization")).map(_.head.value).collect {
          case v if v.regionMatches(true, 0, "DPoP ", 0, 5) => v.drop(5).trim
        }
        token match
          case None => underlying.run(request)
          case Some(t) =>
            def send(n: Option[String]) =
              cats.effect.Resource.eval(key.proof[F](request.method.name, urlOf(request.uri), Some(t), n)).flatMap { proof =>
                underlying.run(request.putHeaders(Header.Raw(HeaderName, proof)))
              }
            cats.effect.Resource.eval(nonce.get).flatMap(send).flatMap { response =>
              val asked = response.headers.get(NonceHeader).map(_.head.value)
              val wantsNonce = response.status.code == 401 &&
                response.headers.get(AuthChallenge.HeaderName).exists(_.toList.exists(_.value.contains("use_dpop_nonce")))
              asked match
                case Some(n) if wantsNonce => cats.effect.Resource.eval(nonce.set(Some(n))) >> send(Some(n))
                case Some(n)               => cats.effect.Resource.eval(nonce.set(Some(n))).as(response)
                case None                  => cats.effect.Resource.pure(response)
            }
      }
    }

  private def urlOf(uri: Uri): String = uri.copy(query = org.http4s.Query.empty, fragment = None).renderString
