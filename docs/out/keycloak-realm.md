# Signing in through Keycloak: the `iron-mcp` realm

This guide sets up the Keycloak realm that iron-mcp's examples sign in against, and runs the **lazy-auth** demo (in iron-mcp-apps) with real sign-in. The same steps fit any server built on `iron-mcp-auth`.

A Keycloak **realm** is one separate set of users, clients and login settings. The `iron-mcp` realm is defined in this repository (`modules/auth/keycloak/iron-mcp-realm.json`) and created by `scripts/keycloak-realm.sh`. Until it exists, public tools work but every sign-in fails: Keycloak answers `404` for the realm.

---

## 1. Choose `MCP_RESOURCE` first

`MCP_RESOURCE` is the MCP server's public URL, **exactly as clients reach it**. Everything hangs off it:

- the script writes it into each `mcp:*` scope's Audience mapper, so every token Keycloak issues names it in `aud`;
- the server is built from the same URL (`ResourceIdentity`) and refuses any token whose `aud` does not contain it (RFC 8707);
- the server's `401` points clients at the metadata derived from it.

The server compares canonical forms: lowercase scheme and host, no default port, no trailing slash. Give the script the canonical form too:

```scala
import ironmcp.auth.*

val demo = ResourceIdentity.unsafe("HTTP://LocalHost:3097/mcp/")
// demo: ResourceIdentity = ResourceIdentity(
//   origin = "http://localhost:3097",
//   path = "/mcp"
// )
demo.resource
// res0: String = "http://localhost:3097/mcp"
demo.metadataUrl
// res1: String = "http://localhost:3097/.well-known/oauth-protected-resource/mcp"
```

It must be `https`, except on a loopback host:

```scala
ResourceIdentity.from("http://demo.example.com/mcp")
// res2: Either[String, ResourceIdentity] = Left(
//   "'http://demo.example.com/mcp' must be https (got http, and the host is not loopback)"
// )
ResourceIdentity.from("https://demo.example.com/mcp")
// res3: Either[String, ResourceIdentity] = Right(
//   ResourceIdentity(origin = "https://demo.example.com", path = "/mcp")
// )
```

Where the lazy-auth demo listens depends on how it is started, so pick the URL from the way you run it:

| How it runs | `MCP_RESOURCE` |
| :--- | :--- |
| `lazyAuthServer/run` (its `Main`; `PORT` defaults to 3097) | `http://localhost:3097/mcp` |
| `scripts/run-all.sh` (all examples, from `BASE_PORT`, default 3201) | the URL it prints for lazy-auth-server, e.g. `http://localhost:3207/mcp` |
| `EXAMPLE=lazy-auth-server scripts/run-all.sh` | `http://localhost:3201/mcp` |
| behind a tunnel or `tailscale serve` | the public `https://…/mcp` URL, also passed to the server as `PUBLIC_URL` |

One realm audience means one server. If two servers share the realm and both URLs go into `aud`, a token for one is accepted by the other, which is the replay RFC 8707 exists to stop. Give a second server its own scopes (or its own realm).

---

## 2. Create the realm

On a machine that can reach Keycloak, with the admin account's credentials:

```bash
KC_URL=https://danarch.tail09c16c.ts.net:10000 \
KC_ADMIN_USER=admin KC_ADMIN_PASSWORD='…' \
MCP_RESOURCE=http://localhost:3097/mcp \
HOST_REDIRECT_URIS=https://claude.ai/api/mcp/auth_callback \
scripts/keycloak-realm.sh --test-user alice
```

- `--test-user alice` asks for a password (14 characters or more) and creates the user.
- `HOST_REDIRECT_URIS` (comma-separated) is where the `iron-mcp-host` client may send users back: the host's OAuth callback. Without it, only loopback is allowed.
- The script is idempotent: run it again with a new `MCP_RESOURCE` and the audience mappers are replaced.

It prints the realm, its issuer, and the lines that turn on the live tests:

```text
Realm:   https://danarch.tail09c16c.ts.net:10000/realms/iron-mcp
Issuer:  https://danarch.tail09c16c.ts.net:10000/realms/iron-mcp

For KeycloakIntegrationSuite (keep these out of the repo):
  export IRON_MCP_KC_REALM=https://danarch.tail09c16c.ts.net:10000/realms/iron-mcp
  export IRON_MCP_KC_CLIENT=iron-mcp-ci
  export IRON_MCP_KC_SECRET=…
  export IRON_MCP_KC_RESOURCE=http://localhost:3097/mcp
```

### Which client is for whom

| Client | Who uses it | Notes |
| :--- | :--- | :--- |
| `iron-mcp-host` | an MCP host signing users in (Claude, other Apps hosts) | public, PKCE S256, consent, **no DPoP**; give the host this client ID |
| `iron-mcp-public` | iron-mcp's own `OAuthClient` | public, PKCE, loopback redirects, **DPoP required** |
| `iron-mcp-machine` | a machine client (Client Credentials extension) | `private_key_jwt`; register its key with `--machine-jwks` |
| `iron-mcp-ci` | `KeycloakIntegrationSuite` and the lazy-auth live test | client secret |
| `iron-mcp-introspector` | the server's own RFC 7662 calls | client secret |

ext-apps' **basic-host** does no OAuth, so it can render the demo and call its public tools, but it cannot sign in. Use a host that does OAuth for the sign-in path.

---

## 3. Run the demo against it

In `~/iron-mcp-apps`:

```bash
KEYCLOAK_REALM=https://danarch.tail09c16c.ts.net:10000/realms/iron-mcp \
PUBLIC_URL=http://localhost:3097/mcp \
sbt "lazyAuthServer/run"
```

`PUBLIC_URL` must be the same string as `MCP_RESOURCE`: it is the server's `ResourceIdentity`. `KEYCLOAK_REALM` must be the realm's issuer as the script printed it.

The server is built the same way any `iron-mcp-auth` server is. Here is the wiring the demo uses, from the environment:

```scala
import cats.effect.IO
import org.http4s.Uri
import org.http4s.client.Client

def validatorFromEnv(http: Client[IO]): IO[(ResourceIdentity, TokenValidator[IO])] =
  val realm    = sys.env.getOrElse("KEYCLOAK_REALM", "https://danarch.tail09c16c.ts.net:10000/realms/iron-mcp")
  val resource = ResourceIdentity.unsafe(sys.env.getOrElse("PUBLIC_URL", "http://localhost:3097/mcp"))
  JwksProvider
    .remote[IO](http, Uri.unsafeFromString(s"$realm/protocol/openid-connect/certs"))
    .map(keys => resource -> TokenValidator.jwks[IO](keys, TokenValidator.Config(realm, resource)))
```

Then, from the host:

1. Add the server with client ID `iron-mcp-host`. A host on this machine (Claude Code, Claude Desktop) can use `http://localhost:3097/mcp`, with its loopback callback in `HOST_REDIRECT_URIS`. A host in the cloud (claude.ai) cannot reach localhost: serve the demo at a public `https` URL and use that as `MCP_RESOURCE` and `PUBLIC_URL`.
2. Call `show_auth_button`. It needs no sign-in, and the View shows "Not signed in. Locked: get_secret, revoke_auth_token."
3. Click **Sign in**. The View calls `unlock_secret`, the server answers `401`, and the host sends you to Keycloak. Sign in as `alice` and consent to `mcp:read`. The host retries, and the View shows "Signed in as alice (mcp:read)."
4. Click **Auth me** to get the secret, then **Revoke token**: the next call gets `401` and the host signs in again.

---

## 4. When sign-in fails

**The host signs in, then every call is `401 invalid_token`.** Almost always `MCP_RESOURCE` and the server's URL differ (`3207` against `3097`, `127.0.0.1` against `localhost`, a trailing path). The audit log shows `"reason":"audience"`, and the caller sees only this fixed text:

```scala
AuthError.WrongAudience(demo.resource, List("http://localhost:3207/mcp")).publicDescription
// res4: String = "The access token was issued for a different resource"
AuthError.WrongAudience(demo.resource, List("http://localhost:3207/mcp")).description
// res5: String = "audience [http://localhost:3207/mcp], expected 'http://localhost:3097/mcp' (RFC 8707)"
```

Fix it by running the script again with the right `MCP_RESOURCE`, or by starting the server with a matching `PUBLIC_URL`.

**Keycloak says "Invalid parameter: redirect_uri".** The host's callback is not among `HOST_REDIRECT_URIS`. Run the script again with it.

**Keycloak refuses the token request with a DPoP error.** The host is using `iron-mcp-public`, which requires DPoP. Give it `iron-mcp-host` instead.

**`401` with `"reason":"issuer"`.** `KEYCLOAK_REALM` is not the issuer exactly as Keycloak prints it (scheme, host, port, `/realms/iron-mcp`).

**`404` for the realm, or for `.well-known/openid-configuration`.** The realm has not been created. Run section 2.

**The live tests are skipped.** Export the four `IRON_MCP_KC_*` lines and run `sbt "auth/Test/testFull"` here, or `sbt "lazyAuthServer/Test/testFull"` in iron-mcp-apps. `IRON_MCP_KC_RESOURCE` must equal the `MCP_RESOURCE` the realm was made with.
