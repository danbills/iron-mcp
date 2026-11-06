package ironmcp
package auth

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder

/** Against a live Keycloak realm (the `iron-mcp` realm from
  * `modules/auth/keycloak/`). Skipped, and reported as skipped, unless these
  * are set:
  *
  *   - `IRON_MCP_KC_REALM`    the realm URL, e.g. https://danarch.tail09c16c.ts.net:10000/realms/iron-mcp
  *   - `IRON_MCP_KC_CLIENT`   a confidential client with the client credentials grant
  *   - `IRON_MCP_KC_SECRET`   its secret
  *   - `IRON_MCP_KC_RESOURCE` the MCP resource its audience mapper adds
  */
class KeycloakIntegrationSuite extends CatsEffectSuite:

  private val env = List("IRON_MCP_KC_REALM", "IRON_MCP_KC_CLIENT", "IRON_MCP_KC_SECRET", "IRON_MCP_KC_RESOURCE").map(k => k -> sys.env.get(k)).toMap

  private def setting(k: String) = env(k).get

  test("live Keycloak: tokens are audience-bound, typed and verifiable; step-up widens them"):
    assume(env.values.forall(_.isDefined), s"set ${env.collect { case (k, None) => k }.mkString(", ")} to run against Keycloak")
    EmberClientBuilder.default[IO].build.use { http =>
      val kc       = new KeycloakClient[IO](http, Uri.unsafeFromString(setting("IRON_MCP_KC_REALM")))
      val auth     = ClientAuth.SecretPost(setting("IRON_MCP_KC_CLIENT"), setting("IRON_MCP_KC_SECRET"))
      val resource = ResourceIdentity.unsafe(setting("IRON_MCP_KC_RESOURCE"))
      for
        oidc      <- kc.discover
        keys      <- JwksProvider.remote[IO](http, Uri.unsafeFromString(oidc.jwks_uri))
        validator  = TokenValidator.jwks[IO](keys, TokenValidator.Config(oidc.issuer, resource))
        read      <- kc.clientCredentials(oidc, auth, Set("mcp:read"))
        claims1   <- validator.validate(read.access_token)
        both      <- kc.clientCredentials(oidc, auth, Set("mcp:read", "mcp:export"))
        claims2   <- validator.validate(both.access_token)
        otherAud   = TokenValidator.jwks[IO](keys, TokenValidator.Config(oidc.issuer, "https://not-this-server.example/mcp"))
        wrong     <- otherAud.validate(read.access_token)
      yield
        assert(claims1.isRight, claims1.toString)
        assert(claims1.toOption.get.scopes.contains("mcp:read"))
        assert(!claims1.toOption.get.scopes.contains("mcp:export"))
        assert(claims2.toOption.exists(_.hasScopes(Set("mcp:read", "mcp:export"))), claims2.toString)
        assertEquals(wrong.left.map(_.kind), Left("audience"))
    }
