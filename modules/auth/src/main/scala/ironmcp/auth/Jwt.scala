package ironmcp
package auth

import io.circe.*
import io.circe.parser.parse as parseJson
import io.circe.syntax.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.Key
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/** Compact JWS parsing and minting over [[Jws]], reflection-free.
  *
  * Parsing checks only the form: three parts of strict base64url (no padding,
  * RFC 7515 §2), two JSON objects, a size cap, an `alg`, and no `crit`
  * extensions (we understand none, so RFC 7515 §4.1.11 says refuse).
  */
object Jwt:

  private val b64Decoder = Base64.getUrlDecoder
  private val b64Encoder = Base64.getUrlEncoder.withoutPadding

  /** Tokens larger than this are refused before anything is decoded. */
  val DefaultMaxBytes: Int = 8 * 1024

  def base64UrlDecode(s: String): Array[Byte] = b64Decoder.decode(s)

  def base64UrlEncode(bytes: Array[Byte]): String = b64Encoder.encodeToString(bytes)

  private val base64Url = "^[A-Za-z0-9_-]*$".r

  final case class JwtParts(
      header: JsonObject,
      payload: Json,
      signingInput: String,
      signature: Array[Byte],
      algorithm: String,
      keyId: Option[String]
  ):
    /** The header's `typ`, lowercased, with any `application/` prefix dropped (RFC 7515 §4.1.9). */
    def typ: Option[String] =
      header("typ").flatMap(_.asString).map(_.toLowerCase.stripPrefix("application/"))

  def parse(token: String, maxBytes: Int = DefaultMaxBytes): Either[AuthError, JwtParts] =
    if token.length > maxBytes then Left(AuthError.TokenTooLarge(token.length, maxBytes))
    else
      val parts = token.split('.')
      if parts.length != 3 || token.endsWith(".") then Left(AuthError.MalformedToken("a JWS has three parts"))
      else if !parts.forall(p => base64Url.matches(p)) then Left(AuthError.MalformedToken("the parts are not base64url"))
      else
        def json(part: String, what: String) =
          scala.util.Try(new String(base64UrlDecode(part), UTF_8)).toEither
            .left.map(_ => AuthError.MalformedToken(s"$what is not base64url"))
            .flatMap(text => parseJson(text).left.map(_ => AuthError.MalformedToken(s"$what is not JSON")))
        for
          headerJson <- json(parts(0), "the header")
          header     <- headerJson.asObject.toRight(AuthError.MalformedToken("the header is not an object"))
          payload    <- json(parts(1), "the payload")
          _          <- Either.cond(payload.isObject, (), AuthError.MalformedToken("the payload is not an object"))
          signature  <- scala.util.Try(base64UrlDecode(parts(2))).toEither.left.map(_ => AuthError.MalformedToken("the signature is not base64url"))
          alg        <- header("alg").flatMap(_.asString).toRight(AuthError.MalformedToken("the header has no 'alg'"))
          _          <- Either.cond(!header.contains("crit"), (), AuthError.MalformedToken("'crit' extensions are not supported"))
          kid        <- header("kid") match
            case None                    => Right(None)
            case Some(k) if k.isString   => Right(k.asString)
            case Some(_)                 => Left(AuthError.MalformedToken("'kid' is not a string"))
        yield JwtParts(header, payload, s"${parts(0)}.${parts(1)}", signature, alg, kid)

  /** Checks `parts`' signature with `key`, if its `alg` is one of `allowed`. */
  def verify(parts: JwtParts, key: Key, allowed: Set[JwsAlgorithm]): Either[AuthError, JwsAlgorithm] =
    for
      alg <- algorithm(parts, allowed)
      _   <- Either.cond(Jws.verify(alg, key, parts.signingInput.getBytes(UTF_8), parts.signature), (), AuthError.InvalidSignature(s"${alg.name} signature does not verify"))
    yield alg

  /** The token's algorithm, if it is on the allowlist. `none` never is. */
  def algorithm(parts: JwtParts, allowed: Set[JwsAlgorithm]): Either[AuthError, JwsAlgorithm] =
    JwsAlgorithm.fromName(parts.algorithm).filter(allowed.contains).toRight(AuthError.UnsupportedAlgorithm(parts.algorithm))

  /** Mints a compact JWS. `alg` is set in the header from `algorithm`. */
  def sign(algorithm: JwsAlgorithm, key: Key, header: JsonObject, payload: Json): String =
    val h64   = base64UrlEncode(header.add("alg", algorithm.name.asJson).asJson.noSpaces.getBytes(UTF_8))
    val p64   = base64UrlEncode(payload.noSpaces.getBytes(UTF_8))
    val input = s"$h64.$p64"
    s"$input.${base64UrlEncode(Jws.sign(algorithm, key, input.getBytes(UTF_8)))}"

  /** An HMAC key from secret bytes. */
  def hmacKey(secret: Array[Byte]): Key = new SecretKeySpec(secret, "HmacSHA256")

  /** Mints an HS256 JWT: for tests. */
  def signHs256(header: Json, payload: Json, secret: Array[Byte]): String =
    sign(JwsAlgorithm.HS256, hmacKey(secret), header.asObject.getOrElse(JsonObject.empty), payload)

  /** Mints an RS256 JWT: for tests. */
  def signRs256(header: Json, payload: Json, privateKey: java.security.PrivateKey): String =
    sign(JwsAlgorithm.RS256, privateKey, header.asObject.getOrElse(JsonObject.empty), payload)
