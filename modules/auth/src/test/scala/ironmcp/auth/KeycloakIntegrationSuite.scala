package ironmcp
package auth

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder

class KeycloakIntegrationSuite extends CatsEffectSuite:

  val keycloakRealm = "https://danarch.tail09c16c.ts.net:10000/realms/hacker-bros"
  val clientId = "iron-mcp-client"
  val clientSecret = "iron-mcp-secret-12345"

  test("Live Keycloak: OIDC discovery, client credentials grant, RS256 JWKS verification, and step-up union"):
    EmberClientBuilder.default[IO].build.use { client =>
      val kc = new KeycloakClient[IO](client, Uri.unsafeFromString(keycloakRealm))

      // Check if Keycloak is reachable; if not, skip gracefully for hermetic offline builds
      kc.discover.attempt.flatMap {
        case Left(_) =>
          IO(println("Keycloak on danarch not reachable; skipping live integration test."))
        case Right(oidc) =>
          for
            _ <- IO(assertEquals(oidc.issuer, keycloakRealm))
            tokenEndpoint = Uri.unsafeFromString(oidc.token_endpoint)
            jwksUri = Uri.unsafeFromString(oidc.jwks_uri)

            // 1. Initial grant for mcp:read
            tok1 <- kc.clientCredentials(tokenEndpoint, clientId, clientSecret, scopes = Set("mcp:read"))
            _ = assert(tok1.grantedScopes.contains("mcp:read"))
            _ = assert(!tok1.grantedScopes.contains("mcp:export"))

            // 2. RS256 token signature verification against Keycloak's published JWKS
            jwksProvider <- JwksProvider.keycloak[IO](client, jwksUri)
            validator = TokenValidator.rs256[IO](
              jwksProvider,
              TokenValidator.Config(expectedIssuer = Some(keycloakRealm))
            )

            claims1 <- validator.validate(tok1.access_token)
            _ = assert(claims1.isRight)
            c1 = claims1.toOption.get
            _ = assertEquals(c1.iss, keycloakRealm)
            _ = assert(c1.scopes.contains("mcp:read"))

            // 3. Step-up: acquire token for the UNION (mcp:read mcp:export)
            tok2 <- kc.clientCredentials(tokenEndpoint, clientId, clientSecret, scopes = Set("mcp:read", "mcp:export"))
            _ = assert(tok2.grantedScopes.contains("mcp:read"))
            _ = assert(tok2.grantedScopes.contains("mcp:export"))

            claims2 <- validator.validate(tok2.access_token)
            _ = assert(claims2.isRight)
            c2 = claims2.toOption.get
            _ = assert(c2.scopes.contains("mcp:read"))
            _ = assert(c2.scopes.contains("mcp:export"))
          yield ()
      }
    }
