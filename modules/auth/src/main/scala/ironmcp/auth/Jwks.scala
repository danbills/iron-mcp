package ironmcp
package auth

import cats.effect.{Deferred, Ref, Temporal}
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.`Cache-Control`

import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.{ECPublicKey, EdECPublicKey, RSAPublicKey}
import java.security.spec.*
import java.security.{AlgorithmParameters, KeyFactory, MessageDigest, PublicKey}
import scala.concurrent.duration.*

/** A JSON Web Key (RFC 7517) holding a public key: RSA, EC (P-256, P-384) or
  * OKP (Ed25519, RFC 8037).
  */
final case class Jwk(
    kty: String,
    alg: Option[String] = None,
    kid: Option[String] = None,
    use: Option[String] = None,
    key_ops: Option[List[String]] = None,
    n: Option[String] = None,
    e: Option[String] = None,
    crv: Option[String] = None,
    x: Option[String] = None,
    y: Option[String] = None,
    d: Option[String] = None
):

  /** The key, if it may verify signatures: `use` (when present) is `sig`,
    * `key_ops` (when present) allows `verify`, it carries no private part, and
    * an `EC` point lies on its curve.
    */
  def toVerificationKey: Either[String, VerificationKey] =
    for
      _ <- Either.cond(d.isEmpty, (), "the JWK holds a private key")
      _ <- Either.cond(use.forall(_ == "sig"), (), s"the JWK is for '${use.getOrElse("")}', not signatures")
      _ <- Either.cond(key_ops.forall(_.contains("verify")), (), "the JWK's key_ops do not allow verify")
      declared <- alg.traverse(name => JwsAlgorithm.fromName(name).toRight(s"the JWK is for '$name'"))
      _ <- declared.traverse(a => Either.cond(a.keyType == kty, (), s"the JWK's alg '${a.name}' does not fit kty '$kty'"))
      key <- toPublicKey
    yield VerificationKey(kid, key, kty, declared, crv)

  /** The public key itself, with no check of what it may be used for. */
  def toPublicKey: Either[String, PublicKey] =
    def bytes(field: Option[String], name: String) =
      field.toRight(s"missing '$name'").flatMap(v => Either.catchNonFatal(Jwt.base64UrlDecode(v)).leftMap(_ => s"'$name' is not base64url"))
    kty match
      case "RSA" =>
        for
          mod <- bytes(n, "n")
          exp <- bytes(e, "e")
          key <- Either
            .catchNonFatal(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, mod), new BigInteger(1, exp))))
            .leftMap(_.getMessage)
          _ <- Either.cond(key.asInstanceOf[RSAPublicKey].getModulus.bitLength >= Jws.MinRsaBits, (), s"RSA key below ${Jws.MinRsaBits} bits")
        yield key
      case "EC" =>
        for
          curve <- crv.toRight("missing 'crv'")
          (name, size) <- curve match
            case "P-256" => Right(("secp256r1", 32))
            case "P-384" => Right(("secp384r1", 48))
            case other   => Left(s"unsupported curve '$other'")
          xb <- bytes(x, "x")
          yb <- bytes(y, "y")
          _  <- Either.cond(xb.length == size && yb.length == size, (), s"'x' and 'y' must be $size bytes for $curve")
          key <- Jwk.ecPublicKey(name, new BigInteger(1, xb), new BigInteger(1, yb))
        yield key
      case "OKP" =>
        for
          _  <- Either.cond(crv.contains("Ed25519"), (), s"unsupported OKP curve '${crv.getOrElse("")}'")
          xb <- bytes(x, "x")
          _  <- Either.cond(xb.length == 32, (), "an Ed25519 'x' is 32 bytes")
          key <- Either
            .catchNonFatal(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Jwk.Ed25519Prefix ++ xb)))
            .leftMap(_.getMessage)
        yield key
      case other => Left(s"unsupported kty '$other'")

  /** RFC 7638 thumbprint: base64url SHA-256 of the required members, sorted. */
  def thumbprint: Either[String, String] =
    val members: Either[String, List[(String, String)]] = kty match
      case "RSA" => (e.toRight("missing 'e'"), n.toRight("missing 'n'")).mapN((e, n) => List("e" -> e, "kty" -> kty, "n" -> n))
      case "EC" =>
        (crv.toRight("missing 'crv'"), x.toRight("missing 'x'"), y.toRight("missing 'y'"))
          .mapN((c, x, y) => List("crv" -> c, "kty" -> kty, "x" -> x, "y" -> y))
      case "OKP" => (crv.toRight("missing 'crv'"), x.toRight("missing 'x'")).mapN((c, x) => List("crv" -> c, "kty" -> kty, "x" -> x))
      case other => Left(s"unsupported kty '$other'")
    members.map { ms =>
      val canonical = ms.map((k, v) => s"${Json.fromString(k).noSpaces}:${Json.fromString(v).noSpaces}").mkString("{", ",", "}")
      Jwt.base64UrlEncode(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(UTF_8)))
    }

object Jwk:
  given Codec[Jwk] =
    val derived = Codec.AsObject.derived[Jwk]
    Codec.from(derived, derived.mapJson(_.dropNullValues))

  /** The X.509 SubjectPublicKeyInfo header in front of a raw Ed25519 key. */
  private[auth] val Ed25519Prefix: Array[Byte] =
    Array(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00).map(_.toByte)

  /** A public key as a JWK, for publishing (a JWKS, a DPoP proof). */
  def fromPublicKey(key: PublicKey, kid: Option[String] = None, alg: Option[JwsAlgorithm] = None): Either[String, Jwk] =
    def b64(i: BigInteger, size: Int) =
      val raw = i.toByteArray.dropWhile(_ == 0)
      Jwt.base64UrlEncode(Array.fill[Byte](math.max(0, size - raw.length))(0) ++ raw)
    key match
      case k: RSAPublicKey =>
        val unsigned = (i: BigInteger) => Jwt.base64UrlEncode(i.toByteArray.dropWhile(_ == 0))
        Right(Jwk("RSA", alg.map(_.name), kid, Some("sig"), n = Some(unsigned(k.getModulus)), e = Some(unsigned(k.getPublicExponent))))
      case k: ECPublicKey =>
        val size = (k.getParams.getCurve.getField.getFieldSize + 7) / 8
        val crv  = if size == 32 then "P-256" else if size == 48 then "P-384" else ""
        Either.cond(crv.nonEmpty, (), "unsupported curve").as(
          Jwk("EC", alg.map(_.name), kid, Some("sig"), crv = Some(crv), x = Some(b64(k.getW.getAffineX, size)), y = Some(b64(k.getW.getAffineY, size)))
        )
      case k: EdECPublicKey if k.getParams.getName.equalsIgnoreCase("Ed25519") =>
        Right(Jwk("OKP", alg.map(_.name), kid, Some("sig"), crv = Some("Ed25519"), x = Some(Jwt.base64UrlEncode(k.getEncoded.takeRight(32)))))
      case other => Left(s"unsupported key type ${other.getAlgorithm}")

  /** An EC public key, refused unless the point lies on the named curve. */
  private[auth] def ecPublicKey(curveName: String, x: BigInteger, y: BigInteger): Either[String, PublicKey] =
    Either
      .catchNonFatal {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(new ECGenParameterSpec(curveName))
        val spec  = params.getParameterSpec(classOf[ECParameterSpec])
        val curve = spec.getCurve
        val p     = curve.getField.asInstanceOf[ECFieldFp].getP
        val onCurve =
          x.signum >= 0 && x.compareTo(p) < 0 && y.signum >= 0 && y.compareTo(p) < 0 &&
            y.modPow(BigInteger.TWO, p) == x.pow(3).add(curve.getA.multiply(x)).add(curve.getB).mod(p)
        if !onCurve then throw new IllegalArgumentException("the point is not on the curve")
        KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec))
      }
      .leftMap(_.getMessage)

final case class JwksKeySet(keys: List[Jwk])

object JwksKeySet:
  given Codec[JwksKeySet] = Codec.AsObject.derived

  /** The keys of a JWKS document that may verify signatures; the rest (an
    * encryption key, an unknown type) are dropped.
    */
  def usable(set: JwksKeySet): KeySet = KeySet(set.keys.flatMap(_.toVerificationKey.toOption))

/** A public key that may verify signatures, with what its JWK said about it. */
final case class VerificationKey(
    kid: Option[String],
    key: PublicKey,
    kty: String,
    alg: Option[JwsAlgorithm],
    crv: Option[String]
):
  /** Whether this key may check a signature made with `algorithm`. */
  def fits(algorithm: JwsAlgorithm): Boolean =
    algorithm.keyType == kty &&
      alg.forall(_ == algorithm) &&
      algorithm.curve.forall(c => crv.contains(c)) &&
      Jws.keyFits(algorithm, key).isRight

/** The keys a validator chooses from. */
final case class KeySet(keys: List[VerificationKey]):

  /** The key for a token's `kid` and `alg`. Without a `kid`, a token is only
    * matched when exactly one key fits its algorithm, so a missing `kid` never
    * means "try whichever key comes first".
    */
  def select(kid: Option[String], alg: JwsAlgorithm): Either[KeySet.Miss, VerificationKey] =
    kid match
      case Some(id) =>
        val named = keys.filter(_.kid.contains(id))
        if named.isEmpty then Left(KeySet.Miss.Unknown)
        else named.find(_.fits(alg)).toRight(KeySet.Miss.Unfit)
      case None =>
        keys.filter(_.fits(alg)) match
          case one :: Nil => Right(one)
          case Nil        => Left(KeySet.Miss.Unknown)
          case _          => Left(KeySet.Miss.Ambiguous)

  def contains(kid: String): Boolean = keys.exists(_.kid.contains(kid))

object KeySet:
  enum Miss:
    case Unknown, Unfit, Ambiguous

  def of(keys: VerificationKey*): KeySet = KeySet(keys.toList)

/** Where a validator gets the key for a token. */
trait JwksProvider[F[_]]:
  def select(kid: Option[String], alg: JwsAlgorithm): F[Either[AuthError, VerificationKey]]

object JwksProvider:

  private def miss(kid: Option[String])(m: KeySet.Miss): AuthError = m match
    case KeySet.Miss.Unknown   => AuthError.UnknownKey(kid)
    case KeySet.Miss.Unfit     => AuthError.InvalidSignature(s"key '${kid.getOrElse("")}' does not fit the token's algorithm")
    case KeySet.Miss.Ambiguous => AuthError.MalformedToken("the token has no 'kid' and several keys could check it")

  /** Fixed keys, for tests and single-key deployments. */
  def static[F[_]: cats.Applicative](keys: KeySet): JwksProvider[F] =
    (kid, alg) => keys.select(kid, alg).leftMap(miss(kid)).pure[F]

  /** The keys of a JWKS document, parsed once. */
  def fromJwks[F[_]: cats.Applicative](jwks: JwksKeySet): JwksProvider[F] = static(JwksKeySet.usable(jwks))

  /** How a remote JWKS is cached and refetched. */
  final case class Settings(
      /** Used when the response has no `Cache-Control: max-age`. */
      defaultTtl: FiniteDuration = 10.minutes,
      /** `max-age` is clamped into this range. */
      minTtl: FiniteDuration = 1.minute,
      maxTtl: FiniteDuration = 24.hours,
      /** An unknown `kid` refetches at most this often, so tokens with made-up
        * `kid`s cannot turn into a flood of requests at the authorization
        * server.
        */
      minRefetchInterval: FiniteDuration = 30.seconds
  )

  /** Keys fetched from `jwksUri` (an authorization server's `jwks_uri`).
    *
    *   - The URI must be `https` (loopback `http` is allowed for development).
    *   - Concurrent lookups share one fetch.
    *   - The cache lives for the response's `max-age`, clamped by `settings`.
    *   - A `kid` the cache does not know refetches, at most once per
    *     `minRefetchInterval`; in between it is simply unknown.
    *   - If a fetch fails, the keys already held stay in use; with none held,
    *     the answer is [[AuthError.KeysUnavailable]] (a `503`), never a pass.
    */
  def remote[F[_]: Temporal](client: Client[F], jwksUri: Uri, settings: Settings = Settings()): F[JwksProvider[F]] =
    ResourceIdentity
      .requireSecure(jwksUri.renderString)
      .leftMap(reason => new IllegalArgumentException(s"jwks_uri: $reason"))
      .liftTo[F] *>
      Ref.of[F, Cache[F]](Cache(None, 0L, 0L, None)).map(new Remote(client, jwksUri, settings, _))

  private final case class Cache[F[_]](
      keys: Option[KeySet],
      expiresAt: Long,
      lastFetchAt: Long,
      inFlight: Option[Deferred[F, Either[Throwable, KeySet]]]
  )

  private final class Remote[F[_]](client: Client[F], uri: Uri, settings: Settings, cache: Ref[F, Cache[F]])(using F: Temporal[F])
      extends JwksProvider[F]:

    def select(kid: Option[String], alg: JwsAlgorithm): F[Either[AuthError, VerificationKey]] =
      for
        now   <- F.realTime.map(_.toMillis)
        state <- cache.get
        known = state.keys.exists(ks => kid.forall(ks.contains))
        fresh = now < state.expiresAt
        cooling = now - state.lastFetchAt < settings.minRefetchInterval.toMillis
        keys <-
          if known && fresh then F.pure(state.keys)
          else if cooling then F.pure(state.keys) // stale or unknown, but fetched moments ago
          else refresh(now).map(_.orElse(state.keys))
      yield keys match
        case None     => Left(AuthError.KeysUnavailable(s"no keys from $uri"))
        case Some(ks) => ks.select(kid, alg).leftMap(miss(kid))

    /** One fetch at a time; everyone else waits for it. `None` if it failed. */
    private def refresh(now: Long): F[Option[KeySet]] =
      Deferred[F, Either[Throwable, KeySet]].flatMap { mine =>
        cache
          .modify {
            case c @ Cache(_, _, _, Some(running)) => (c, running.get)
            case c =>
              val fetchAndStore = fetch.attempt.flatTap {
                case Right((keys, ttl)) => cache.set(Cache(Some(keys), now + ttl.toMillis, now, None))
                case Left(_)            => cache.update(_.copy(lastFetchAt = now, inFlight = None))
              }
              (c.copy(inFlight = Some(mine)), F.uncancelable(_ => fetchAndStore.map(_.map(_._1)).flatTap(mine.complete(_).void)))
          }
          .flatten
          .map(_.toOption)
      }

    private def fetch: F[(KeySet, FiniteDuration)] =
      client.run(Request[F](Method.GET, uri)).use { response =>
        if !response.status.isSuccess then F.raiseError(new RuntimeException(s"JWKS fetch from $uri: ${response.status}"))
        else
          response.as[JwksKeySet].map { set =>
            val maxAge = response.headers.get[`Cache-Control`].flatMap(_.values.collectFirst {
              case CacheDirective.`max-age`(age) => age.toSeconds.seconds
            })
            val wanted = maxAge.getOrElse(settings.defaultTtl)
            val ttl    = if wanted < settings.minTtl then settings.minTtl else if wanted > settings.maxTtl then settings.maxTtl else wanted
            (JwksKeySet.usable(set), ttl)
          }
      }

  extension (set: KeySet)
    /** The set as a JWKS document, e.g. to serve a client's `jwks_uri`. */
    def toJwks: Json =
      Json.obj("keys" -> set.keys.flatMap(k => Jwk.fromPublicKey(k.key, k.kid, k.alg).toOption).asJson)
