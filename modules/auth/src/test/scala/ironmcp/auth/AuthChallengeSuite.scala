package ironmcp
package auth

import munit.FunSuite

class AuthChallengeSuite extends FunSuite:

  test("parses several challenges, quoted and token values, escapes and odd spacing"):
    val parsed = AuthChallenge.parse(
      List(
        """Bearer error="insufficient_scope", scope="a b",resource_metadata="https://x/.well-known/oauth-protected-resource/mcp" , DPoP algs="ES256 EdDSA", error=invalid_token""",
        """Basic realm="say \"hi\"""""
      )
    )
    assertEquals(parsed.map(_.scheme), List("Bearer", "DPoP", "Basic"))
    assertEquals(parsed(0).scopes, Set("a", "b"))
    assertEquals(parsed(0).param("resource_metadata"), Some("https://x/.well-known/oauth-protected-resource/mcp"))
    assertEquals(parsed(1).param("algs"), Some("ES256 EdDSA"))
    assertEquals(parsed(1).error, Some("invalid_token"))
    assertEquals(parsed(2).param("realm"), Some("say \"hi\""))

  test("render and parse round-trip"):
    val c = AuthChallenge("Bearer", Map("scope" -> "a b", "error_description" -> "x \"y\" \\ z"))
    assertEquals(AuthChallenge.parse(List(c.render)), List(c))

  test("garbage never loops or throws"):
    List("", ",,,", "Bearer", "Bearer =", "Bearer a=\"unterminated", "= = =", "Bearer a=b c=d").foreach(h => AuthChallenge.parse(List(h)))
