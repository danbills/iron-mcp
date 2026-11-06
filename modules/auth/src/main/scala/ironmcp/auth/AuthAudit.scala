package ironmcp
package auth

import cats.Applicative
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*

/** One authorization decision. It names who asked, for what, and why the
  * answer was what it was. It never holds the token.
  *
  * @param allowed  the decision
  * @param reason   a short stable name: `ok`, `public`, or an [[AuthError.kind]]
  *                 (`expired`, `audience`, `insufficient_scope`, `unlisted`, ...)
  * @param detail   the details of a refusal ([[AuthError.description]])
  * @param method   the MCP method
  * @param target   the tool, resource or prompt (`tool:export_full_data`)
  * @param required the scopes the target needs
  * @param granted  the scopes the token grants
  * @param jkt      the thumbprint of the key a DPoP-bound token is bound to
  */
final case class AuthEvent(
    at: Long,
    allowed: Boolean,
    reason: String,
    detail: Option[String],
    method: String,
    target: String,
    subject: Option[String],
    clientId: Option[String],
    issuer: Option[String],
    jti: Option[String],
    jkt: Option[String],
    required: Set[String],
    granted: Set[String]
)

object AuthEvent:
  given Encoder[AuthEvent] = Encoder.instance { e =>
    Json
      .obj(
        "at"       -> e.at.asJson,
        "allowed"  -> e.allowed.asJson,
        "reason"   -> e.reason.asJson,
        "detail"   -> e.detail.asJson,
        "method"   -> e.method.asJson,
        "target"   -> e.target.asJson,
        "subject"  -> e.subject.asJson,
        "clientId" -> e.clientId.asJson,
        "issuer"   -> e.issuer.asJson,
        "jti"      -> e.jti.asJson,
        "jkt"      -> e.jkt.asJson,
        "required" -> e.required.toList.sorted.asJson,
        "granted"  -> e.granted.toList.sorted.asJson
      )
      .dropNullValues
  }

/** Where authorization decisions go: a log, a SIEM, OpenTelemetry. */
trait AuthAudit[F[_]]:
  def record(event: AuthEvent): F[Unit]

object AuthAudit:
  def noop[F[_]: Applicative]: AuthAudit[F] = _ => Applicative[F].unit

  /** One JSON object per line, written with `write` (stdout, a file, a queue). */
  def jsonLines[F[_]](write: String => F[Unit]): AuthAudit[F] = event => write(event.asJson.noSpaces)

  /** Both sinks, in order. */
  def both[F[_]: Applicative](a: AuthAudit[F], b: AuthAudit[F]): AuthAudit[F] = event => a.record(event) *> b.record(event)
