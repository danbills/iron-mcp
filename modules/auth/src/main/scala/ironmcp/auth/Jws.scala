package ironmcp
package auth

import java.math.BigInteger
import java.security.*
import java.security.interfaces.{ECKey, EdECKey, RSAKey}
import java.security.spec.{MGF1ParameterSpec, PSSParameterSpec}
import javax.crypto.{Mac, SecretKey}

/** The JWS algorithms iron-mcp signs and verifies (RFC 7518, RFC 8037).
  *
  * Which algorithms a validator accepts is fixed by its configuration, never
  * by the token: a token naming `none`, an unknown algorithm, or one outside
  * the allowlist is refused before any key is looked up.
  */
enum JwsAlgorithm(val name: String, val keyType: String):
  case RS256 extends JwsAlgorithm("RS256", "RSA")
  case RS384 extends JwsAlgorithm("RS384", "RSA")
  case RS512 extends JwsAlgorithm("RS512", "RSA")
  case PS256 extends JwsAlgorithm("PS256", "RSA")
  case PS384 extends JwsAlgorithm("PS384", "RSA")
  case PS512 extends JwsAlgorithm("PS512", "RSA")
  case ES256 extends JwsAlgorithm("ES256", "EC")
  case ES384 extends JwsAlgorithm("ES384", "EC")
  case EdDSA extends JwsAlgorithm("EdDSA", "OKP")

  /** HMAC with a shared secret. Anyone who can check the token can also mint
    * one, so it is for tests and single-process setups only.
    */
  case HS256 extends JwsAlgorithm("HS256", "oct")

  /** The curve an `EC` or `OKP` key must be on for this algorithm. */
  def curve: Option[String] = this match
    case ES256 => Some("P-256")
    case ES384 => Some("P-384")
    case EdDSA => Some("Ed25519")
    case _     => None

  def isAsymmetric: Boolean = this != HS256

object JwsAlgorithm:
  /** Every public-key algorithm: the default allowlist of a validator. */
  val asymmetric: Set[JwsAlgorithm] = values.filter(_.isAsymmetric).toSet

  def fromName(name: String): Option[JwsAlgorithm] = values.find(_.name == name)

/** Raw JWS signatures over `java.security`, with the checks a library must
  * make itself: key type and size against the algorithm, and the JOSE form of
  * ECDSA signatures (fixed-width `R || S`, RFC 7518 §3.4), which the JDK does
  * not speak.
  */
object Jws:

  /** RSA keys below this many bits are refused (RFC 7518 §3.3). */
  val MinRsaBits = 2048

  /** HMAC secrets below this many bytes are refused (RFC 7518 §3.2). */
  val MinHmacBytes = 32

  /** Whether `key` may be used with `alg`: the right type, curve and size. */
  def keyFits(alg: JwsAlgorithm, key: Key): Either[String, Unit] =
    import JwsAlgorithm.*
    alg match
      case RS256 | RS384 | RS512 | PS256 | PS384 | PS512 =>
        key match
          case k: RSAKey if k.getModulus.bitLength >= MinRsaBits => Right(())
          case k: RSAKey => Left(s"RSA key of ${k.getModulus.bitLength} bits is below $MinRsaBits")
          case _         => Left(s"${alg.name} needs an RSA key")
      case ES256 | ES384 =>
        val bits = if alg == ES256 then 256 else 384
        key match
          case k: ECKey if k.getParams.getCurve.getField.getFieldSize == bits => Right(())
          case _: ECKey => Left(s"${alg.name} needs a key on ${alg.curve.get}")
          case _        => Left(s"${alg.name} needs an EC key")
      case EdDSA =>
        key match
          case k: EdECKey if k.getParams.getName.equalsIgnoreCase("Ed25519") => Right(())
          case _ => Left("EdDSA needs an Ed25519 key")
      case HS256 =>
        key match
          case k: SecretKey if Option(k.getEncoded).exists(_.length >= MinHmacBytes) => Right(())
          case _: SecretKey => Left(s"HS256 needs a secret of at least $MinHmacBytes bytes")
          case _            => Left("HS256 needs a secret key")

  /** Verifies `signature` over `input`. Any malformed signature or unusable
    * key is `false`, never an exception.
    */
  def verify(alg: JwsAlgorithm, key: Key, input: Array[Byte], signature: Array[Byte]): Boolean =
    keyFits(alg, key).isRight && {
      try
        alg match
          case JwsAlgorithm.HS256 =>
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            MessageDigest.isEqual(mac.doFinal(input), signature)
          case JwsAlgorithm.ES256 | JwsAlgorithm.ES384 =>
            joseToDer(signature, coordinateBytes(alg)) match
              case None => false
              case Some(der) =>
                val verifier = signer(alg)
                verifier.initVerify(key.asInstanceOf[PublicKey])
                verifier.update(input)
                verifier.verify(der)
          case _ =>
            val verifier = signer(alg)
            verifier.initVerify(key.asInstanceOf[PublicKey])
            verifier.update(input)
            verifier.verify(signature)
      catch case _: GeneralSecurityException | _: ClassCastException | _: IllegalArgumentException => false
    }

  /** Signs `input`, giving ECDSA signatures in their JOSE form. */
  def sign(alg: JwsAlgorithm, key: Key, input: Array[Byte]): Array[Byte] =
    keyFits(alg, key).left.foreach(reason => throw new InvalidKeyException(reason))
    alg match
      case JwsAlgorithm.HS256 =>
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        mac.doFinal(input)
      case _ =>
        val s = signer(alg)
        s.initSign(key.asInstanceOf[PrivateKey])
        s.update(input)
        val sig = s.sign()
        if alg == JwsAlgorithm.ES256 || alg == JwsAlgorithm.ES384 then derToJose(sig, coordinateBytes(alg))
        else sig

  private def coordinateBytes(alg: JwsAlgorithm): Int = if alg == JwsAlgorithm.ES256 then 32 else 48

  private def signer(alg: JwsAlgorithm): Signature =
    import JwsAlgorithm.*
    alg match
      case RS256 => Signature.getInstance("SHA256withRSA")
      case RS384 => Signature.getInstance("SHA384withRSA")
      case RS512 => Signature.getInstance("SHA512withRSA")
      case PS256 => pss("SHA-256", MGF1ParameterSpec.SHA256, 32)
      case PS384 => pss("SHA-384", MGF1ParameterSpec.SHA384, 48)
      case PS512 => pss("SHA-512", MGF1ParameterSpec.SHA512, 64)
      case ES256 => Signature.getInstance("SHA256withECDSA")
      case ES384 => Signature.getInstance("SHA384withECDSA")
      case EdDSA => Signature.getInstance("Ed25519")
      case HS256 => throw new NoSuchAlgorithmException("HS256 is a MAC")

  private def pss(digest: String, mgf: MGF1ParameterSpec, saltBytes: Int): Signature =
    val s = Signature.getInstance("RSASSA-PSS")
    s.setParameter(new PSSParameterSpec(digest, "MGF1", mgf, saltBytes, 1))
    s

  /** `R || S`, each `n` bytes, to an ASN.1 `SEQUENCE { INTEGER, INTEGER }`.
    * A zero `R` or `S` is refused here, whatever the JDK would do with it
    * (the "psychic signatures" bug, CVE-2022-21449).
    */
  private[auth] def joseToDer(raw: Array[Byte], n: Int): Option[Array[Byte]] =
    if raw.length != 2 * n then None
    else
      val r = new BigInteger(1, raw.slice(0, n))
      val s = new BigInteger(1, raw.slice(n, 2 * n))
      if r.signum == 0 || s.signum == 0 then None
      else Some(derSequence(derInteger(r) ++ derInteger(s)))

  private def derInteger(x: BigInteger): Array[Byte] =
    val bytes = x.toByteArray // two's complement: a leading 0 keeps it positive
    Array(0x02.toByte) ++ derLength(bytes.length) ++ bytes

  private def derSequence(content: Array[Byte]): Array[Byte] =
    Array(0x30.toByte) ++ derLength(content.length) ++ content

  private def derLength(n: Int): Array[Byte] =
    if n < 0x80 then Array(n.toByte)
    else if n < 0x100 then Array(0x81.toByte, n.toByte)
    else Array(0x82.toByte, (n >> 8).toByte, n.toByte)

  /** The JDK's DER ECDSA signature to the JOSE `R || S` form. */
  private[auth] def derToJose(der: Array[Byte], n: Int): Array[Byte] =
    def length(at: Int): (Int, Int) = // (length, bytes the length took)
      val first = der(at) & 0xff
      if first < 0x80 then (first, 1)
      else
        val count = first & 0x7f
        ((0 until count).foldLeft(0)((acc, i) => (acc << 8) | (der(at + 1 + i) & 0xff)), 1 + count)
    def integer(at: Int): (BigInteger, Int) = // (value, next offset)
      if der(at) != 0x02 then throw new SignatureException("malformed DER signature")
      val (len, took) = length(at + 1)
      val start       = at + 1 + took
      (new BigInteger(1, der.slice(start, start + len)), start + len)
    if der.isEmpty || der(0) != 0x30 then throw new SignatureException("malformed DER signature")
    val (_, took) = length(1)
    val (r, next) = integer(1 + took)
    val (s, _)    = integer(next)
    fixed(r, n) ++ fixed(s, n)

  private def fixed(x: BigInteger, n: Int): Array[Byte] =
    val bytes    = x.toByteArray.dropWhile(_ == 0)
    val unsigned = if bytes.length > n then throw new SignatureException("ECDSA value too long") else bytes
    Array.fill[Byte](n - unsigned.length)(0) ++ unsigned
