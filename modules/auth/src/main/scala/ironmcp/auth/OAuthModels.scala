package ironmcp
package auth

import io.circe.*
import io.circe.syntax.*

/** RFC 9728 Protected Resource Metadata.
  *
  * Served at `/.well-known/oauth-protected-resource` (for root resources) or
  * `/.well-known/oauth-protected-resource/{path}` (for sub-paths).
  *
  * Clients follow this document from the 401/403 challenge's `resource_metadata`
  * parameter to discover the authorization server and supported scopes.
  */
final case class ProtectedResourceMetadata(
    resource: String,
    authorization_servers: List[String],
    scopes_supported: Option[List[String]] = None,
    bearer_methods_supported: Option[List[String]] = Some(List("header")),
    resource_documentation: Option[String] = None
)

object ProtectedResourceMetadata:
  given Codec[ProtectedResourceMetadata] = Codec.AsObject.derived

/** Extracted claims from a validated Bearer JWT.
  *
  * RFC 7519 / RFC 8707 / RFC 6749.
  *
  * @param iss the issuer URL
  * @param sub the subject (caller identity or service account)
  * @param aud the list of audiences (RFC 8707 resource indicators)
  * @param exp expiration time in epoch seconds
  * @param nbf not-before time in epoch seconds
  * @param iat issued-at time in epoch seconds
  * @param scope space-delimited OAuth scopes string
  * @param rawJson full decoded JSON payload for custom claims
  */
final case class TokenClaims(
    iss: String,
    sub: Option[String],
    aud: List[String],
    exp: Option[Long],
    nbf: Option[Long],
    iat: Option[Long],
    scope: Option[String],
    rawJson: Json = Json.obj()
):
  /** Parsed set of granted scopes. */
  lazy val scopes: Set[String] =
    scope.fold(Set.empty[String])(_.split("\\s+").filter(_.nonEmpty).toSet)

  /** Returns true if all required scopes are granted. */
  def hasScopes(required: Set[String]): Boolean =
    required.subsetOf(scopes)

object TokenClaims:
  // Custom decoder because RFC 7519 allows `aud` to be a string or an array of strings.
  given Decoder[TokenClaims] = Decoder.instance { c =>
    for
      iss <- c.get[String]("iss")
      sub <- c.get[Option[String]]("sub")
      aud <- c.get[Option[Json]]("aud").flatMap {
        case None => Right(Nil)
        case Some(j) if j.isString => j.asString.map(s => List(s)).toRight(DecodingFailure("aud not a string", c.history))
        case Some(j) if j.isArray => j.as[List[String]]
        case _ => Right(Nil)
      }
      exp <- c.get[Option[Long]]("exp")
      nbf <- c.get[Option[Long]]("nbf")
      iat <- c.get[Option[Long]]("iat")
      scope <- c.get[Option[String]]("scope")
    yield TokenClaims(iss, sub, aud, exp, nbf, iat, scope, c.value)
  }

  given Encoder[TokenClaims] = Encoder.instance { t =>
    Json.obj(
      "iss" -> t.iss.asJson,
      "sub" -> t.sub.asJson,
      "aud" -> t.aud.asJson,
      "exp" -> t.exp.asJson,
      "nbf" -> t.nbf.asJson,
      "iat" -> t.iat.asJson,
      "scope" -> t.scope.asJson
    )
  }

/** OAuth 2.0 / 2.1 Access Token Response (RFC 6749 Section 5.1). */
final case class TokenResponse(
    access_token: String,
    token_type: String,
    expires_in: Option[Long] = None,
    refresh_token: Option[String] = None,
    scope: Option[String] = None
):
  lazy val grantedScopes: Set[String] =
    scope.fold(Set.empty[String])(_.split("\\s+").filter(_.nonEmpty).toSet)

object TokenResponse:
  given Codec[TokenResponse] = Codec.AsObject.derived

/** Rejection reasons on the Resource Server auth ladder. */
enum AuthError:
  case MissingToken
  case MalformedToken(reason: String)
  case InvalidSignature(reason: String)
  case Expired(exp: Long, now: Long)
  case Premature(nbf: Long, now: Long)
  case IssuerMismatch(expected: String, actual: String)
  case WrongAudience(expectedResource: String, actualAudience: List[String])
  case InsufficientScope(required: Set[String], granted: Set[String])

  def description: String = this match
    case MissingToken => "Missing or non-Bearer Authorization header"
    case MalformedToken(r) => s"Malformed JWT: $r"
    case InvalidSignature(r) => s"Invalid token signature: $r"
    case Expired(exp, now) => s"Token expired at $exp (current time $now)"
    case Premature(nbf, now) => s"Token not valid before $nbf (current time $now)"
    case IssuerMismatch(exp, act) => s"Token issuer is '$act', expected '$exp'"
    case WrongAudience(exp, act) =>
      s"Token audience is '${act.mkString(", ")}' — this is a token for a different resource. This server only accepts tokens minted for '$exp' (RFC 8707 resource indicator)."
    case InsufficientScope(req, granted) =>
      s"Operation requires [${req.mkString(" ")}]; the token grants [${granted.mkString(" ")}]."

/** Strategy for step-up re-authorization requests (§9.7.4). */
enum ScopeStrategy:
  /** Correct behavior: requests `previouslyRequested ∪ challenged`. */
  case Union
  /** Anti-pattern: requests only the missing scopes, discarding held grants. */
  case Naive
