package ironmcp
package auth

import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** One `WWW-Authenticate` challenge (RFC 9110 §11.6.1): a scheme and its
  * parameters.
  */
final case class AuthChallenge(scheme: String, params: Map[String, String]):

  /** The challenge as a header value, every parameter a quoted-string. */
  def render: String =
    if params.isEmpty then scheme
    else s"$scheme ${params.toList.map((k, v) => s"$k=${AuthChallenge.quote(v)}").mkString(", ")}"

  def param(name: String): Option[String] = params.get(name.toLowerCase)

  /** The challenge's `scope`, split. */
  def scopes: Set[String] = param("scope").fold(Set.empty[String])(_.split(' ').filter(_.nonEmpty).toSet)

  def error: Option[String] = param("error")

object AuthChallenge:

  val HeaderName: CIString = CIString("WWW-Authenticate")

  /** A value as a quoted-string: `\` and `"` escaped, and anything outside
    * visible ASCII (control characters, CR and LF above all) replaced, so no
    * value can end the parameter or the header early.
    */
  def quote(value: String): String =
    val safe = value.map(c => if c == '\t' || (c >= ' ' && c < 0x7f) then c else '?')
    "\"" + safe.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case c    => c.toString
    } + "\""

  /** The answer to a request without a usable token or with an invalid one:
    * `401`, or `400` for a malformed header, `503` when keys cannot be
    * fetched. A missing token gets no `error` (RFC 6750 §3.1); `scope`, when
    * given, tells the client what to ask for (MCP authorization, "Scope
    * Selection Strategy").
    */
  def unauthorized[F[_]](
      error: AuthError,
      resourceMetadataUrl: String,
      scope: Set[String] = Set.empty,
      schemes: List[String] = List("Bearer"),
      dpopAlgs: Set[JwsAlgorithm] = Set.empty
  ): Response[F] =
    error match
      case AuthError.KeysUnavailable(_) =>
        Response[F](Status.ServiceUnavailable)
          .putHeaders(Header.Raw(CIString("Retry-After"), "5"))
          .withEntity(body(error))
          .withContentType(`Content-Type`(MediaType.application.json))
      case _ =>
        val status = error match
          case AuthError.InvalidRequest(_) => Status.BadRequest
          case _                           => Status.Unauthorized
        val common = List("resource_metadata" -> resourceMetadataUrl) ++ Option.when(scope.nonEmpty)("scope" -> scope.toList.sorted.mkString(" "))
        val errorParams =
          if error == AuthError.MissingToken then Nil
          else List("error" -> error.code, "error_description" -> error.publicDescription)
        val challenges = schemes.map { scheme =>
          val algs = Option.when(scheme == "DPoP" && dpopAlgs.nonEmpty)("algs" -> dpopAlgs.map(_.name).toList.sorted.mkString(" "))
          // A DPoP proof problem belongs on the DPoP challenge only.
          val errs = error match
            case AuthError.InvalidDpopProof(_) | AuthError.DpopRequired if scheme != "DPoP" => Nil
            case _                                                                       => errorParams
          AuthChallenge(scheme, (errs ++ common ++ algs).toMap).render
        }
        val response = Response[F](status).withEntity(body(error)).withContentType(`Content-Type`(MediaType.application.json))
        // One header per challenge; putHeaders would keep only the last.
        response.withHeaders(Headers(response.headers.headers ++ challenges.map(c => Header.Raw(HeaderName, c))))

  /** `403 insufficient_scope`, naming every scope the request needs (not just
    * the missing ones), so a client can ask for exactly that set (SEP-2350).
    * With no scopes (a target nobody may use), there is nothing to ask for and
    * the challenge has no `scope`.
    */
  def forbidden[F[_]](required: Set[String], resourceMetadataUrl: String, scheme: String = "Bearer"): Response[F] =
    val error = AuthError.InsufficientScope(required, Set.empty)
    val challenge = AuthChallenge(
      scheme,
      Map(
        "error"             -> error.code,
        "error_description" -> error.publicDescription,
        "resource_metadata" -> resourceMetadataUrl
      ) ++ Option.when(required.nonEmpty)("scope" -> required.toList.sorted.mkString(" "))
    )
    Response[F](Status.Forbidden)
      .putHeaders(Header.Raw(HeaderName, challenge.render))
      .withEntity(body(error))
      .withContentType(`Content-Type`(MediaType.application.json))

  private def body(error: AuthError): Json =
    Json.obj(
      "error"             -> (if error.code.isEmpty then "invalid_request" else error.code).asJson,
      "error_description" -> error.publicDescription.asJson
    )

  /** Parses `WWW-Authenticate` header values into challenges. Tolerant of
    * whitespace and of token (unquoted) values; a parameter it cannot read
    * ends that challenge.
    */
  def parse(values: List[String]): List[AuthChallenge] = values.flatMap(parseOne)

  private def parseOne(header: String): List[AuthChallenge] =
    val s = header
    var i = 0
    val out = List.newBuilder[AuthChallenge]
    def skipWs(): Unit = while i < s.length && (s(i) == ' ' || s(i) == '\t') do i += 1
    def token(): String =
      val start = i
      while i < s.length && isTchar(s(i)) do i += 1
      s.substring(start, i)
    def quoted(): Option[String] =
      if i >= s.length || s(i) != '"' then None
      else
        i += 1
        val b = new StringBuilder
        var closed = false
        while i < s.length && !closed do
          s(i) match
            case '\\' if i + 1 < s.length => b += s(i + 1); i += 2
            case '"'                      => closed = true; i += 1
            case c                        => b += c; i += 1
        Option.when(closed)(b.result())
    while i < s.length do
      skipWs()
      while i < s.length && s(i) == ',' do { i += 1; skipWs() }
      val scheme = token()
      if scheme.isEmpty then i = s.length
      else
        val params = Map.newBuilder[String, String]
        var more = true
        while more do
          skipWs()
          val mark = i
          val name = token()
          skipWs()
          if name.nonEmpty && i < s.length && s(i) == '=' then
            i += 1
            skipWs()
            val value = if i < s.length && s(i) == '"' then quoted() else Some(token())
            value match
              case Some(v) =>
                params += name.toLowerCase -> v
                skipWs()
                if i < s.length && s(i) == ',' then i += 1 else more = false
              case None => more = false; i = s.length
          else
            // Not a parameter: the next challenge's scheme (or token68).
            i = mark
            more = false
        out += AuthChallenge(scheme, params.result())
    out.result()

  private def isTchar(c: Char): Boolean =
    c.isLetterOrDigit && c < 0x80 || "!#$%&'*+-.^_`|~/".contains(c)
