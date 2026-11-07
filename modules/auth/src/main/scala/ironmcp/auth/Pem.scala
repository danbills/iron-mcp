package ironmcp
package auth

import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, PrivateKey, PublicKey}
import java.util.Base64
import scala.util.Try

/** Keys from PEM text: what `openssl genpkey` and most key stores write.
  *
  *   - private keys: PKCS #8 (`-----BEGIN PRIVATE KEY-----`)
  *   - public keys: SubjectPublicKeyInfo (`-----BEGIN PUBLIC KEY-----`)
  *
  * The key type (EC, RSA, Ed25519) is read from the key itself. Legacy
  * `BEGIN EC PRIVATE KEY` and `BEGIN RSA PRIVATE KEY` files are refused with
  * the `openssl` command that converts them, rather than parsed by hand.
  */
object Pem:

  private val keyFactories = List("EC", "RSA", "Ed25519")

  def privateKey(pem: String): Either[String, PrivateKey] =
    block(pem, "PRIVATE KEY").flatMap { der =>
      firstOf(algorithm => KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der)))
        .toRight("not a PKCS #8 EC, RSA or Ed25519 private key")
    }

  def publicKey(pem: String): Either[String, PublicKey] =
    block(pem, "PUBLIC KEY").flatMap { der =>
      firstOf(algorithm => KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der)))
        .toRight("not an EC, RSA or Ed25519 public key")
    }

  /** The key a PEM file holds. Reads the file; nothing is cached. */
  def privateKeyFile(path: java.nio.file.Path): Either[String, PrivateKey] =
    Try(java.nio.file.Files.readString(path)).toEither.left.map(e => s"cannot read $path: ${e.getMessage}").flatMap(privateKey)

  def publicKeyFile(path: java.nio.file.Path): Either[String, PublicKey] =
    Try(java.nio.file.Files.readString(path)).toEither.left.map(e => s"cannot read $path: ${e.getMessage}").flatMap(publicKey)

  /** PEM text for a public key, for registering it with a server. */
  def encode(key: PublicKey): String =
    val body = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(key.getEncoded)
    s"-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"

  private def firstOf[A](attempt: String => A): Option[A] =
    keyFactories.iterator.map(a => Try(attempt(a)).toOption).collectFirst { case Some(k) => k }

  private def block(pem: String, label: String): Either[String, Array[Byte]] =
    val begin = s"-----BEGIN $label-----"
    val end   = s"-----END $label-----"
    val legacy = """-----BEGIN (EC|RSA) PRIVATE KEY-----""".r.findFirstMatchIn(pem).map(_.group(1))
    legacy match
      case Some(kind) => Left(s"a legacy $kind key: convert it with `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem`")
      case None =>
        val from = pem.indexOf(begin)
        val to   = pem.indexOf(end)
        if from < 0 || to < from then Left(s"no '$begin' block")
        else
          val body = pem.substring(from + begin.length, to).filterNot(_.isWhitespace)
          Try(Base64.getDecoder.decode(body)).toEither.left.map(_ => "the PEM body is not base64")
