package ironmcp
package auth

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import ironmcp.http.McpHeaders
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator}

/** Keys, tokens and MCP requests shared by the auth suites. */
object AuthFixtures:

  val issuer   = "https://auth.example.com"
  val resource: ResourceIdentity = ResourceIdentity.unsafe("http://localhost:5309/mcp")
  val audience: String = resource.resource

  val hmacSecret: Array[Byte] = "auth-fixtures-hmac-secret-at-least-32-bytes".getBytes(UTF_8)

  lazy val rsa: KeyPair = gen("RSA", _.initialize(2048))
  lazy val rsa2: KeyPair = gen("RSA", _.initialize(2048))
  lazy val ec: KeyPair  = gen("EC", _.initialize(new ECGenParameterSpec("secp256r1")))
  lazy val ed: KeyPair  = gen("Ed25519", _ => ())

  private def gen(alg: String, init: KeyPairGenerator => Unit): KeyPair =
    val g = KeyPairGenerator.getInstance(alg)
    init(g)
    g.generateKeyPair()

  def now: Long = System.currentTimeMillis() / 1000

  /** An access token's claims, good unless overridden. */
  def claims(
      scope: String = "",
      aud: Json = Json.fromString(audience),
      iss: String = issuer,
      exp: Option[Long] = Some(now + 3600),
      sub: String = "alice",
      extra: List[(String, Json)] = Nil
  ): Json =
    Json.fromJsonObject(
      JsonObject.fromIterable(
        List("iss" -> iss.asJson, "sub" -> sub.asJson, "aud" -> aud, "iat" -> now.asJson)
          ++ exp.map(e => "exp" -> e.asJson)
          ++ Option.when(scope.nonEmpty)("scope" -> scope.asJson)
          ++ extra
      )
    )

  /** A token signed with `alg`; the header says `typ: at+jwt` unless told otherwise. */
  def sign(alg: JwsAlgorithm, key: java.security.Key, payload: Json, kid: Option[String] = Some("k1"), typ: Option[String] = Some("at+jwt")): String =
    Jwt.sign(alg, key, JsonObject.fromIterable(kid.map("kid" -> _.asJson).toList ++ typ.map("typ" -> _.asJson)), payload)

  def hs(payload: Json, typ: Option[String] = Some("at+jwt")): String =
    sign(JwsAlgorithm.HS256, Jwt.hmacKey(hmacSecret), payload, kid = None, typ = typ)

  def rs(payload: Json, kid: Option[String] = Some("k1"), typ: Option[String] = Some("at+jwt")): String =
    sign(JwsAlgorithm.RS256, rsa.getPrivate, payload, kid, typ)

  def vkey(kp: KeyPair, kid: String, alg: Option[JwsAlgorithm] = None): VerificationKey =
    Jwk.fromPublicKey(kp.getPublic, Some(kid), alg).flatMap(_.toVerificationKey).fold(e => throw new IllegalStateException(e), identity)

  val config: TokenValidator.Config = TokenValidator.Config(issuer, resource)

  val hsValidator: TokenValidator[IO] = TokenValidator.sharedSecret[IO](hmacSecret, config.copy(algorithms = Set(JwsAlgorithm.HS256)))

  def jwksValidator(keys: VerificationKey*): TokenValidator[IO] =
    TokenValidator.jwks[IO](JwksProvider.static[IO](KeySet(keys.toList)), config)

  val meta = """{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

  /** A Streamable HTTP request as a client sends it, with the headers mirrored
    * from the body.
    */
  def mcp(
      method: String,
      name: Option[String] = None,
      token: Option[String] = None,
      authorization: Option[String] = None,
      path: Uri = uri"/mcp"
  ): Request[IO] =
    val params = method match
      case "tools/call"     => s"""{"name":${Json.fromString(name.getOrElse("")).noSpaces},"arguments":{},"_meta":$meta}"""
      case "resources/read" => s"""{"uri":${Json.fromString(name.getOrElse("")).noSpaces},"_meta":$meta}"""
      case "prompts/get"    => s"""{"name":${Json.fromString(name.getOrElse("")).noSpaces},"_meta":$meta}"""
      case _                => s"""{"_meta":$meta}"""
    Request[IO](Method.POST, path)
      .withEntity(s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
      .putHeaders(
        Header.Raw(CIString("MCP-Protocol-Version"), "2026-07-28"),
        Header.Raw(CIString("Mcp-Method"), method),
        Header.Raw(CIString("Host"), "localhost:5309")
      )
      .putHeaders(name.map(n => Header.Raw(McpHeaders.Name, McpHeaders.encode(n))).toList)
      .putHeaders(authorization.orElse(token.map(t => s"Bearer $t")).map(a => Header.Raw(CIString("Authorization"), a)).toList)

  def challenge(res: Response[IO]): String =
    res.headers.get(CIString("WWW-Authenticate")).map(_.head.value).getOrElse("")
