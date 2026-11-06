package ironmcp
package auth

import io.circe.*

/** RFC 9728 Protected Resource Metadata.
  *
  * Served at `/.well-known/oauth-protected-resource` followed by the
  * resource's path (see [[ResourceIdentity.metadataUrl]]). Clients follow the
  * challenge's `resource_metadata` parameter here to find the authorization
  * server and the scopes on offer.
  */
final case class ProtectedResourceMetadata(
    resource: String,
    authorization_servers: List[String],
    scopes_supported: Option[List[String]] = None,
    bearer_methods_supported: Option[List[String]] = Some(List("header")),
    resource_name: Option[String] = None,
    resource_documentation: Option[String] = None,
    dpop_signing_alg_values_supported: Option[List[String]] = None,
    dpop_bound_access_tokens_required: Option[Boolean] = None
)

object ProtectedResourceMetadata:
  given Codec[ProtectedResourceMetadata] =
    val derived = Codec.AsObject.derived[ProtectedResourceMetadata]
    Codec.from(derived, derived.mapJson(_.dropNullValues))

/** The claims of an access token that passed every check.
  *
  * @param iss   the issuer
  * @param sub   the subject: the user, or the client acting for itself
  * @param aud   the audiences (RFC 8707 resource indicators)
  * @param exp   expiry, epoch seconds; required, a token without it is refused
  * @param nbf   not-before, epoch seconds
  * @param iat   issued-at, epoch seconds
  * @param scope the granted scopes, space-delimited (from `scope`, or an `scp` array)
  * @param rawJson the whole payload, for claims of your own ([[claim]])
  */
final case class TokenClaims(
    iss: String,
    sub: Option[String],
    aud: List[String],
    exp: Long,
    nbf: Option[Long] = None,
    iat: Option[Long] = None,
    scope: Option[String] = None,
    rawJson: Json = Json.obj()
):
  /** The granted scopes. */
  lazy val scopes: Set[String] =
    scope.fold(Set.empty[String])(_.split("\\s+").filter(_.nonEmpty).toSet)

  def hasScopes(required: Set[String]): Boolean = required.subsetOf(scopes)

  /** Any claim of the payload, decoded. */
  def claim[A: Decoder](name: String): Option[A] = rawJson.hcursor.get[A](name).toOption

  /** The token's own ID (RFC 7519 `jti`). */
  def jti: Option[String] = claim[String]("jti")

  /** The login session it belongs to (OIDC `sid`). */
  def sid: Option[String] = claim[String]("sid")

  /** The client it was issued to: `client_id` (RFC 9068), else `azp`. */
  def clientId: Option[String] = claim[String]("client_id").orElse(claim[String]("azp"))

  /** The SHA-256 thumbprint of the key the token is bound to (DPoP,
    * RFC 9449 `cnf.jkt`).
    */
  def boundKeyThumbprint: Option[String] = rawJson.hcursor.downField("cnf").get[String]("jkt").toOption

  def preferredUsername: Option[String] = claim[String]("preferred_username")

object TokenClaims:
  /** NumericDate may be fractional (RFC 7519 §2). */
  private val numericDate: Decoder[Long] = Decoder.decodeLong.or(Decoder.decodeDouble.map(_.toLong))

  /** `aud` may be a string or an array (RFC 7519 §4.1.3); scopes may be a
    * `scope` string or an `scp` array.
    */
  given Decoder[TokenClaims] = Decoder.instance { c =>
    for
      iss <- c.get[String]("iss")
      sub <- c.get[Option[String]]("sub")
      aud <- c.downField("aud").focus match
        case None                       => Right(Nil)
        case Some(j) if j.isNull        => Right(Nil)
        case Some(j) if j.isString      => j.as[String].map(List(_))
        case Some(j)                    => j.as[List[String]]
      exp   <- c.downField("exp").as(using numericDate)
      nbf   <- c.downField("nbf").as(using Decoder.decodeOption(using numericDate))
      iat   <- c.downField("iat").as(using Decoder.decodeOption(using numericDate))
      scope <- c.get[Option[String]]("scope")
      scp   <- c.get[Option[List[String]]]("scp").orElse(Right(None))
    yield TokenClaims(iss, sub, aud, exp, nbf, iat, scope.orElse(scp.map(_.mkString(" "))), c.value)
  }

  given Encoder[TokenClaims] = Encoder.instance(_.rawJson)

/** OAuth 2.1 token response (RFC 6749 §5.1, RFC 8693 §2.2.1). */
final case class TokenResponse(
    access_token: String,
    token_type: String,
    expires_in: Option[Long] = None,
    refresh_token: Option[String] = None,
    scope: Option[String] = None,
    issued_token_type: Option[String] = None
):
  lazy val grantedScopes: Set[String] =
    scope.fold(Set.empty[String])(_.split("\\s+").filter(_.nonEmpty).toSet)

object TokenResponse:
  given Codec[TokenResponse] =
    val derived = Codec.AsObject.derived[TokenResponse]
    Codec.from(derived, derived.mapJson(_.dropNullValues))

/** Why a request was refused.
  *
  * [[description]] has the details (the audience the token named, the
  * cryptographic error) and goes to the audit log only. What the caller sees
  * is [[publicDescription]], a fixed sentence per kind, so nothing from the
  * token or from inside the server is echoed back.
  */
enum AuthError:
  case MissingToken
  case InvalidRequest(reason: String)
  case MalformedToken(reason: String)
  case TokenTooLarge(bytes: Int, limit: Int)
  case UnsupportedAlgorithm(alg: String)
  case UnknownKey(kid: Option[String])
  case InvalidSignature(reason: String)
  case WrongTokenType(reason: String)
  case MissingClaim(name: String)
  case Expired(exp: Long, now: Long)
  case Premature(nbf: Long, now: Long)
  case IssuedInFuture(iat: Long, now: Long)
  case TooOld(iat: Long, now: Long)
  case IssuerMismatch(expected: String, actual: String)
  case WrongAudience(expectedResource: String, actualAudience: List[String])
  case Revoked(reason: String)
  case Inactive
  case KeysUnavailable(reason: String)
  case InsufficientScope(required: Set[String], granted: Set[String])
  case InvalidDpopProof(reason: String)
  case DpopRequired

  /** The OAuth error code (RFC 6750 §3.1, RFC 9449 §7.1). */
  def code: String = this match
    case MissingToken                => ""
    case InvalidRequest(_)           => "invalid_request"
    case InsufficientScope(_, _)     => "insufficient_scope"
    case InvalidDpopProof(_)         => "invalid_dpop_proof"
    case KeysUnavailable(_)          => "temporarily_unavailable"
    case _                           => "invalid_token"

  /** What the caller is told: fixed text, nothing from the token. */
  def publicDescription: String = this match
    case MissingToken               => "An access token is required"
    case InvalidRequest(_)          => "The Authorization header is malformed"
    case MalformedToken(_)          => "The access token is malformed"
    case TokenTooLarge(_, _)        => "The access token is too large"
    case UnsupportedAlgorithm(_)    => "The access token is signed with an algorithm this server does not accept"
    case UnknownKey(_)              => "The access token is signed with an unknown key"
    case InvalidSignature(_)        => "The access token signature is invalid"
    case WrongTokenType(_)          => "The token is not an access token"
    case MissingClaim(_)            => "The access token is missing a required claim"
    case Expired(_, _)              => "The access token has expired"
    case Premature(_, _)            => "The access token is not yet valid"
    case IssuedInFuture(_, _)       => "The access token was issued in the future"
    case TooOld(_, _)               => "The access token is too old"
    case IssuerMismatch(_, _)       => "The access token was issued by an untrusted issuer"
    case WrongAudience(_, _)        => "The access token was issued for a different resource"
    case Revoked(_)                 => "The access token has been revoked"
    case Inactive                   => "The access token is not active"
    case KeysUnavailable(_)         => "The server cannot check tokens right now"
    case InsufficientScope(_, _)    => "The access token does not grant the scope this request needs"
    case InvalidDpopProof(_)        => "The DPoP proof is invalid"
    case DpopRequired               => "This server requires a DPoP-bound access token"

  /** The details, for the audit log. */
  def description: String = this match
    case MissingToken                 => "no access token"
    case InvalidRequest(r)            => s"malformed Authorization header: $r"
    case MalformedToken(r)            => s"malformed JWT: $r"
    case TokenTooLarge(b, l)          => s"token of $b bytes is over the $l byte limit"
    case UnsupportedAlgorithm(a)      => s"algorithm '$a' is not allowed"
    case UnknownKey(kid)              => s"no key '${kid.getOrElse("(none)")}'"
    case InvalidSignature(r)          => s"invalid signature: $r"
    case WrongTokenType(r)            => s"not an access token: $r"
    case MissingClaim(n)              => s"missing claim '$n'"
    case Expired(exp, now)            => s"expired at $exp (now $now)"
    case Premature(nbf, now)          => s"not valid before $nbf (now $now)"
    case IssuedInFuture(iat, now)     => s"issued at $iat, in the future (now $now)"
    case TooOld(iat, now)             => s"issued at $iat, older than allowed (now $now)"
    case IssuerMismatch(exp, act)     => s"issuer '$act', expected '$exp'"
    case WrongAudience(exp, act)      => s"audience [${act.mkString(", ")}], expected '$exp' (RFC 8707)"
    case Revoked(r)                   => s"revoked: $r"
    case Inactive                     => "introspection says inactive"
    case KeysUnavailable(r)           => s"keys unavailable: $r"
    case InsufficientScope(req, g)    => s"requires [${req.toList.sorted.mkString(" ")}], granted [${g.toList.sorted.mkString(" ")}]"
    case InvalidDpopProof(r)          => s"invalid DPoP proof: $r"
    case DpopRequired                 => "a Bearer token where DPoP is required"

  /** A short stable name, for audit records and metrics labels. */
  def kind: String = this match
    case MissingToken            => "missing_token"
    case InvalidRequest(_)       => "invalid_request"
    case MalformedToken(_)       => "malformed"
    case TokenTooLarge(_, _)     => "too_large"
    case UnsupportedAlgorithm(_) => "algorithm"
    case UnknownKey(_)           => "unknown_key"
    case InvalidSignature(_)     => "signature"
    case WrongTokenType(_)       => "token_type"
    case MissingClaim(_)         => "missing_claim"
    case Expired(_, _)           => "expired"
    case Premature(_, _)         => "premature"
    case IssuedInFuture(_, _)    => "future_iat"
    case TooOld(_, _)            => "too_old"
    case IssuerMismatch(_, _)    => "issuer"
    case WrongAudience(_, _)     => "audience"
    case Revoked(_)              => "revoked"
    case Inactive                => "inactive"
    case KeysUnavailable(_)      => "keys_unavailable"
    case InsufficientScope(_, _) => "insufficient_scope"
    case InvalidDpopProof(_)     => "dpop_proof"
    case DpopRequired            => "dpop_required"

/** How a client asks again after a `403 insufficient_scope` (SEP-2350). */
enum ScopeStrategy:
  /** Asks for everything asked for before plus the challenged scopes. */
  case Union

  /** Asks for the challenged scopes alone, dropping what it held: the
    * anti-pattern, kept to show why it fails.
    */
  case Naive
