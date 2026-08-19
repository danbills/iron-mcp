package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given

/** The protocol revision this library implements. There is exactly one, on
  * purpose: 2026-07-28 replaced the `initialize` handshake with `server/discover`,
  * and supporting both revisions would mean two lifecycles in one type surface.
  */
val LatestProtocolVersion: ProtocolVersion = "2026-07-28"

/** Progress tokens, like request ids, are string or number on the wire. */
enum ProgressToken:
  case Num(value: Long)
  case Text(value: NonEmptyString)

object ProgressToken:
  given Encoder[ProgressToken] =
    case ProgressToken.Num(v)  => Json.fromLong(v)
    case ProgressToken.Text(v) => Json.fromString(v)

  given Decoder[ProgressToken] = Decoder.instance { cursor =>
    cursor.as[Long].map(ProgressToken.Num.apply).orElse {
      cursor.as[NonEmptyString].map(ProgressToken.Text.apply)
    }
  }

enum Role:
  case user, assistant

object Role:
  given Encoder[Role] = role => Json.fromString(role.toString)
  given Decoder[Role] = Decoder[String].emap {
    case "user"      => Right(Role.user)
    case "assistant" => Right(Role.assistant)
    case other       => Left(s"not a role: $other")
  }

enum LoggingLevel:
  case debug, info, notice, warning, error, critical, alert, emergency

object LoggingLevel:
  given Encoder[LoggingLevel] = level => Json.fromString(level.toString)
  given Decoder[LoggingLevel] = Decoder[String].emap { raw =>
    values.find(_.toString == raw).toRight(s"not a logging level: $raw")
  }

/** Every result carries a `resultType`. `input_required` is how a server says
  * "I need something from the client before I can answer" — only legal on
  * tools/call, prompts/get and resources/read.
  */
enum ResultType:
  case Complete
  case InputRequired
  case Extension(name: NonEmptyString)

object ResultType:
  given Encoder[ResultType] =
    case ResultType.Complete       => Json.fromString("complete")
    case ResultType.InputRequired  => Json.fromString("input_required")
    case ResultType.Extension(raw) => Json.fromString(raw)

  given Decoder[ResultType] = Decoder[NonEmptyString].map {
    case "complete"       => ResultType.Complete
    case "input_required" => ResultType.InputRequired
    case other            => ResultType.Extension(other)
  }

/** How long a cacheable result may be reused, and by whom. */
enum CacheScope:
  case `public`, `private`

object CacheScope:
  given Encoder[CacheScope] = scope => Json.fromString(scope.toString)
  given Decoder[CacheScope] = Decoder[String].emap {
    case "public"  => Right(CacheScope.`public`)
    case "private" => Right(CacheScope.`private`)
    case other     => Left(s"not a cache scope: $other")
  }

final case class CachePolicy(ttlMs: CacheTtlMs, cacheScope: CacheScope) derives Codec.AsObject

object CachePolicy:
  /** A safe default for per-client data: cache briefly, never share. */
  val privateShortLived: CachePolicy = CachePolicy(0L, CacheScope.`private`)

enum IconTheme:
  case light, dark

object IconTheme:
  given Encoder[IconTheme] = theme => Json.fromString(theme.toString)
  given Decoder[IconTheme] = Decoder[String].emap {
    case "light" => Right(IconTheme.light)
    case "dark"  => Right(IconTheme.dark)
    case other   => Left(s"not an icon theme: $other")
  }

final case class Icon(
    src: Uri,
    mimeType: Option[MimeType] = None,
    sizes: Option[List[NonEmptyString]] = None,
    theme: Option[IconTheme] = None
) derives Codec.AsObject

final case class Annotations(
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None,
    lastModified: Option[NonEmptyString] = None
) derives Codec.AsObject

/** Who a server says it is. Returned in result `_meta`, since there is no
  * handshake in this revision to announce it.
  */
final case class Implementation(
    name: NonEmptyString,
    version: NonEmptyString,
    title: Option[NonEmptyString] = None,
    description: Option[NonEmptyString] = None,
    websiteUrl: Option[Uri] = None,
    icons: Option[List[Icon]] = None
) derives Codec.AsObject
