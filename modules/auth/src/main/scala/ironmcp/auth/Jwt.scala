package ironmcp
package auth

import io.circe.*
import io.circe.parser.parse as parseJson

import java.nio.charset.StandardCharsets.UTF_8
import java.security.*
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Zero-dependency, reflection-free JWT parsing and verification using
  * standard `java.security` and `javax.crypto`.
  *
  * Fully compatible with Scala Native and GraalVM native-image.
  */
object Jwt:

  private val b64Decoder = Base64.getUrlDecoder
  private val b64Encoder = Base64.getUrlEncoder.withoutPadding

  def base64UrlDecode(s: String): Array[Byte] =
    b64Decoder.decode(s)

  def base64UrlEncode(bytes: Array[Byte]): String =
    b64Encoder.encodeToString(bytes)

  final case class JwtParts(
      headerJson: Json,
      payloadJson: Json,
      signingInput: String, // header.payload
      signatureBytes: Array[Byte],
      algorithm: String,
      keyId: Option[String]
  )

  /** Splits and parses an encoded JWT string without signature verification. */
  def parse(token: String): Either[AuthError, JwtParts] =
    val parts = token.trim.split('.')
    if parts.length != 3 then
      Left(AuthError.MalformedToken("JWT must contain exactly three period-delimited parts"))
    else
      for
        headerBytes <- scala.util.Try(base64UrlDecode(parts(0))).toEither.left.map(e =>
          AuthError.MalformedToken(s"Invalid header base64url: ${e.getMessage}")
        )
        payloadBytes <- scala.util.Try(base64UrlDecode(parts(1))).toEither.left.map(e =>
          AuthError.MalformedToken(s"Invalid payload base64url: ${e.getMessage}")
        )
        sigBytes <- scala.util.Try(base64UrlDecode(parts(2))).toEither.left.map(e =>
          AuthError.MalformedToken(s"Invalid signature base64url: ${e.getMessage}")
        )
        headerJson <- parseJson(new String(headerBytes, UTF_8)).left.map(e =>
          AuthError.MalformedToken(s"Header JSON parse error: ${e.getMessage}")
        )
        payloadJson <- parseJson(new String(payloadBytes, UTF_8)).left.map(e =>
          AuthError.MalformedToken(s"Payload JSON parse error: ${e.getMessage}")
        )
        alg <- headerJson.hcursor.get[String]("alg").left.map(e =>
          AuthError.MalformedToken(s"Missing 'alg' in header: ${e.getMessage}")
        )
        kid = headerJson.hcursor.get[Option[String]]("kid").toOption.flatten
      yield JwtParts(
        headerJson = headerJson,
        payloadJson = payloadJson,
        signingInput = s"${parts(0)}.${parts(1)}",
        signatureBytes = sigBytes,
        algorithm = alg,
        keyId = kid
      )

  /** Verifies an RS256 signature against an RSA public key. */
  def verifyRs256(parts: JwtParts, publicKey: PublicKey): Either[AuthError, TokenClaims] =
    if parts.algorithm != "RS256" then
      Left(AuthError.InvalidSignature(s"Expected RS256 algorithm, got '${parts.algorithm}'"))
    else
      try
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(parts.signingInput.getBytes(UTF_8))
        if verifier.verify(parts.signatureBytes) then
          parts.payloadJson.as[TokenClaims].left.map(e => AuthError.MalformedToken(e.getMessage))
        else
          Left(AuthError.InvalidSignature("RSA signature verification failed"))
      catch
        case e: GeneralSecurityException =>
          Left(AuthError.InvalidSignature(s"Cryptographic error: ${e.getMessage}"))

  /** Verifies an HS256 signature against a shared HMAC-SHA256 secret. */
  def verifyHs256(parts: JwtParts, secret: Array[Byte]): Either[AuthError, TokenClaims] =
    if parts.algorithm != "HS256" then
      Left(AuthError.InvalidSignature(s"Expected HS256 algorithm, got '${parts.algorithm}'"))
    else
      try
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(secret, "HmacSHA256"))
        val expected = mac.doFinal(parts.signingInput.getBytes(UTF_8))
        if MessageDigest.isEqual(expected, parts.signatureBytes) then
          parts.payloadJson.as[TokenClaims].left.map(e => AuthError.MalformedToken(e.getMessage))
        else
          Left(AuthError.InvalidSignature("HMAC signature verification failed"))
      catch
        case e: GeneralSecurityException =>
          Left(AuthError.InvalidSignature(s"Cryptographic error: ${e.getMessage}"))

  /** Mints an HS256 signed JWT (useful for tests and offline mocks). */
  def signHs256(header: Json, payload: Json, secret: Array[Byte]): String =
    val h64 = base64UrlEncode(header.noSpaces.getBytes(UTF_8))
    val p64 = base64UrlEncode(payload.noSpaces.getBytes(UTF_8))
    val input = s"$h64.$p64"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    val sig = mac.doFinal(input.getBytes(UTF_8))
    s"$input.${base64UrlEncode(sig)}"

  /** Mints an RS256 signed JWT (useful for tests and offline mocks). */
  def signRs256(header: Json, payload: Json, privateKey: PrivateKey): String =
    val h64 = base64UrlEncode(header.noSpaces.getBytes(UTF_8))
    val p64 = base64UrlEncode(payload.noSpaces.getBytes(UTF_8))
    val input = s"$h64.$p64"
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update(input.getBytes(UTF_8))
    val sig = signer.sign()
    s"$input.${base64UrlEncode(sig)}"
