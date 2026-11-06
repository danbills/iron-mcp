# Authorization in iron-mcp

`iron-mcp-auth` makes an MCP server an OAuth 2.1 **resource server**, and an MCP client a careful OAuth client. It follows the MCP authorization spec (revision `2026-07-28`), RFC 9728, RFC 8707, RFC 9068, RFC 6750, SEP-2350 (step-up) and RFC 7662 (introspection).

**The easy path is the strict one.** A validator needs an issuer and an audience before it compiles. A tool that does not say who may call it can be called by nobody. A token without `exp` is refused. You have to write code to weaken any of this; you never have to write code to get it.

---

## 1. What it stops

| Attack | What stops it |
| :--- | :--- |
| A genuine token minted for another service is replayed here (the downstream trap) | `aud` must equal this server's resource URI (RFC 8707); `TokenValidator.Config` has no default for it |
| A token from another realm or IdP | `iss` must equal the configured issuer |
| An OIDC ID token, or a refresh token, presented as an access token | the token-type profile: `typ: at+jwt` (RFC 9068), or Keycloak's payload `typ: Bearer` |
| `alg: none`; HS256 "signed" with the server's RSA public key (algorithm confusion) | the algorithm allowlist comes from config, never from the token; each key is tied to its type, curve and declared `alg` |
| ECDSA signatures with `r = 0` or `s = 0` (CVE-2022-21449) | refused before the JDK sees them |
| Weak keys; encryption keys in the JWKS | RSA under 2048 bits, `use: enc`, `key_ops` without `verify`, private JWKs, and EC points off their curve are dropped |
| A token with no `kid` checked against "the first key" | without `kid`, a token matches only if exactly one key fits |
| Random `kid`s used to flood the authorization server | one JWKS fetch at a time, and an unknown `kid` refetches at most every 30 s |
| A token that never expires, or not yet valid, or issued in the future | `exp` required; `nbf` and `iat` checked; optional `maxAge` |
| Oversized tokens | refused over 8 KiB before any decoding |
| A forgotten scope rule | `AuthPolicy` denies anything it does not name; `undeclared` lists such tools at startup |
| Calling a tool the caller cannot see | refused at the HTTP layer (`403`) and again inside `ScopedTools` |
| Reading resources or prompts without scope | gated by URI prefix and by name, as tools are |
| Reading or cancelling someone else's task by ID | tasks carry an owner (`TaskServer(owner = ...)`) |
| A header naming one tool while the body calls another | McpHttp checks `Mcp-Name` against the body before auth runs |
| Quote or CRLF injection into `WWW-Authenticate` | every parameter is an escaped quoted-string; descriptions are fixed text, never token contents |
| An invalid token on a public tool treated as anonymous | an invalid token is always `401`, even in optional sign-in |
| A stolen token used after the user signs out | `RevocationList` (`jti`, `sid`, subject) and RFC 7662 introspection, layered on local checks |
| The JWKS endpoint is down | `503`, never a pass |
| "Who did that?" | `AuthAudit`: every decision, with subject, client, target and scopes, never the token; `iron-mcp-otel-auth` turns it into metrics and span events |
| A token copied from a log, a proxy or a compromised client is replayed | DPoP (RFC 9449): tokens bound to the client's key need a fresh signed proof per request; a bound token sent as Bearer is refused |
| A captured DPoP proof is replayed | each proof names method, URL and token hash, is good for two minutes, and its `jti` is remembered |
| A malicious authorization server mixes up the user's sessions | RFC 9207: the redirect's `iss` must match, and is required when the server advertises it |
| A resource server points the client at its own "metadata" or at plain http | metadata must be on the resource's origin; every URL must be `https` (loopback excepted) |
| A hostile resource server asks the client for every scope there is | `StepUpClient(allowedScopes = ...)` refuses challenges outside the set |
| A shared client secret leaks | `ClientAuth.PrivateKeyJwt`: a one-minute signed assertion instead; secrets are never sent to a server that does not list the method |

---

## 2. A protected server, in full

```scala mdoc:silent
import cats.effect.IO
import io.circe.Decoder
import io.github.iltotore.iron.autoRefine
import ironmcp.auth.*
import ironmcp.protocol.Implementation
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import cats.effect.std.SecureRandom
import org.http4s.Uri
import org.http4s.client.Client

final case class ReportQuery(format: Option[String] = None) derives Decoder, JsonSchemaOf

// Each tool says who may call it.
val summary = McpTool[ReportQuery]("read_summary", "Read the summary")(_ => IO.pure("Summary")).requires("mcp:read")
val exporter = McpTool[ReportQuery]("export_full_data", "Export everything")(_ => IO.pure("csv")).requires("mcp:read", "mcp:export")
val ping    = McpTool[ReportQuery]("ping", "Is it up?")(_ => IO.pure("pong")).public
val tools   = List(summary, exporter, ping)

val issuer   = "https://danarch.tail09c16c.ts.net:10000/realms/iron-mcp"
val resource = ResourceIdentity.unsafe("https://reports.example.com/mcp")
val policy   = AuthPolicy.fromTools(tools*)

def routes(http: Client[IO]) =
  for
    _    <- IO.raiseWhen(policy.undeclared(tools*).nonEmpty)(new IllegalStateException("tools without an access rule"))
    keys <- JwksProvider.remote[IO](http, Uri.unsafeFromString(s"$issuer/protocol/openid-connect/certs"))
    validator = TokenValidator.jwks[IO](keys, TokenValidator.Config(issuer, resource))
    config    = McpAuthServer.Config(resource, List(issuer), policy)
  yield McpAuthServer.routesFor(validator, config) { (_, claims) =>
    IO.pure(Right(McpServer[IO](Implementation("reports", "1.0.0"), tools = Some(ScopedTools(ToolSet(tools), policy, Some(claims))))))
  }
```

That is the whole of it:

- `ResourceIdentity` is the server's public URL. It is the metadata's `resource`, the audience every token must carry, the path the routes are mounted on, and the source of the `resource_metadata` URL (`https://reports.example.com/.well-known/oauth-protected-resource/mcp`, per RFC 9728 §3.1). It must be `https` (loopback `http` is allowed for development).
- `.requires(...)`, `.signedIn` and `.public` write the tool's access into its `_meta` under `io.github.danbills/auth`. `AuthPolicy.fromTools` reads it back, so the rule lives on the tool it protects. Hosts and Views can read the same entry to show a lock.
- The scopes in the metadata's `scopes_supported` come from the policy.

### The ladder

| Request | Answer |
| :--- | :--- |
| `Authorization` repeated or malformed, or `access_token` in the query | `400 invalid_request` |
| No token, target not public | `401`, `WWW-Authenticate: Bearer resource_metadata="…", scope="…"` (no `error`, RFC 6750 §3.1) |
| Token invalid in any way | `401 invalid_token`, fixed description |
| Signing keys unreachable | `503` with `Retry-After` |
| Target not in the policy | `403`, no `scope` (there is nothing to ask for) |
| Token lacks the target's scopes | `403 insufficient_scope`, `scope` naming the **full** set the target needs |
| Otherwise | the server `routesFor` built for these claims |

```http
HTTP/1.1 403 Forbidden
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The access token does not grant the scope this request needs", resource_metadata="https://reports.example.com/.well-known/oauth-protected-resource/mcp", scope="mcp:export mcp:read"
```

---

## 3. Access to resources and prompts

```scala mdoc:silent
val fuller = policy
  .resource("file:///public/", Access.Public)
  .resource("file:///reports/", Access.Scopes(Set("mcp:read")))
  .prompt("weekly_digest", Access.SignedIn)
```

The longest matching URI prefix wins, and a URI no prefix matches is refused. Wrap your providers in `ScopedResources` and `ScopedPrompts` (as `ScopedTools`) so listings show only what the caller may use and the providers refuse the rest themselves.

Everything else (`tools/list`, `server/discover`, `tasks/get`, …) needs `policy.methods`, which is `SignedIn` by default.

---

## 4. Optional sign-in

Some servers have tools anyone may call and tools that need a user. `routesForOptional` hands you `Option[TokenClaims]`:

```scala mdoc:silent
def optionalRoutes(validator: TokenValidator[IO]) =
  val open = policy.publicListing // anonymous callers may list and discover
  McpAuthServer.routesForOptional(validator, McpAuthServer.Config(resource, List(issuer), open)) { (_, caller) =>
    IO.pure(Right(McpServer[IO](Implementation("reports", "1.0.0"), tools = Some(ScopedTools(ToolSet(tools), open, caller)))))
  }
```

Anonymous callers see and call the `public` tools. The first call to a protected tool gets the `401` that starts the client's sign-in. A token that is present but invalid is always refused: a client must learn its token is bad, not silently lose its identity.

---

## 5. The validator

`TokenValidator.Config(issuer, resource)` is all you must give. The rest has strict defaults:

| Setting | Default |
| :--- | :--- |
| `algorithms` | `RS256 RS384 RS512 PS256 PS384 PS512 ES256 ES384 EdDSA`. HS256 is never accepted with keys from a JWKS. |
| `profile` | `AccessTokenProfile.standard`: `typ: at+jwt`, or Keycloak's payload `typ: Bearer`. Use `.rfc9068` to insist on the former. |
| `clockSkew` | 60 s, on `exp`, `nbf` and `iat` |
| `maxAge` | none; set it to refuse tokens issued too long ago |
| `maxTokenBytes` | 8 KiB |

`TokenValidator.sharedSecret` (HS256) exists for tests. Whoever can check such a token can mint one.

### Revocation and introspection

A JWT stays valid until it expires. To end it sooner:

```scala mdoc:silent
def withRevocation(local: TokenValidator[IO]) =
  RevocationList.inMemory[IO].map { revoked =>
    // revoked.revokeSession(sid, until), revoked.revokeToken(jti, until),
    // revoked.revokeSubject(iss, sub, before, until): "sign out everywhere"
    (local.withRevocation(revoked), revoked)
  }

def layered(http: Client[IO], local: TokenValidator[IO]) =
  Introspection[IO](
    http,
    Introspection.Config(issuer, resource.resource, s"$issuer/protocol/openid-connect/token/introspect", ClientAuth.SecretBasic("reports-rs", sys.env("RS_SECRET")))
  ).map(remote => TokenValidator.layered(local, remote)(_.scopes.contains("mcp:admin")))
```

`layered` checks every token locally, and also asks the authorization server (RFC 7662) about tokens carrying `mcp:admin`, so revoking an admin's session takes effect within the introspection cache's 30 s rather than at token expiry. Introspection answers are cached by the token's SHA-256, never the token. Any failure to get an answer is a `503`.

---

## 6. The audit trail

```scala mdoc:silent
def audited(validator: TokenValidator[IO]) =
  val audit = AuthAudit.jsonLines[IO](line => IO.println(line))
  McpAuthServer.routesFor(validator, McpAuthServer.Config(resource, List(issuer), policy), audit) { (_, claims) =>
    IO.pure(Right(McpServer[IO](Implementation("reports", "1.0.0"), tools = Some(ScopedTools(ToolSet(tools), policy, Some(claims))))))
  }
```

```json
{"at":1793318400123,"allowed":false,"reason":"insufficient_scope","detail":"requires [mcp:export mcp:read], granted [mcp:read]","method":"tools/call","target":"tool:export_full_data","subject":"8c1f…","clientId":"claude-desktop","issuer":"https://…/realms/iron-mcp","jti":"…","required":["mcp:export","mcp:read"],"granted":["mcp:read"]}
```

`iron-mcp-otel-auth` provides `OtelAuth.audit(meter, tracer)`: a `mcp.auth.decisions` counter labelled by outcome and reason, and an `mcp.auth.decision` event on the current span. A rise in `audience` or `signature` refusals means someone is presenting tokens never meant for this server. Send both with `AuthAudit.both`.

---

## 7. DPoP: tokens a thief cannot use

A Bearer token works for whoever holds it. A DPoP-bound token (RFC 9449) carries the thumbprint of the client's public key (`cnf.jkt`), and every request must come with a fresh **proof**: a small JWT signed with the private key, naming the method, the URL and a hash of the token. A copied token is useless without the key, and a captured proof is useless for any other request.

On the server, it is one setting:

```scala mdoc:silent
val dpopOnly = McpAuthServer.Config(resource, List(issuer), policy, dpop = DpopPolicy.Required)
```

| Policy | Unbound Bearer token | Bound token as Bearer | Bound token with valid proof |
| :--- | :--- | :--- | :--- |
| `Allowed` (default) | accepted | **refused** | accepted |
| `Required` | refused, `WWW-Authenticate: DPoP algs="…"` | refused | accepted |

The metadata advertises `dpop_signing_alg_values_supported`, and `dpop_bound_access_tokens_required` under `Required`. Proofs are checked for type, algorithm, a public `jwk` whose key made the signature, `htm`, `htu` (the resource's public URL), `iat` within two minutes, `ath`, and a `jti` not seen before (`ReplayCache`; in memory by default, `McpAuthServer.Hooks` takes a shared one for several replicas).

On the client, a `Dpop.Key` binds the tokens and signs the proofs:

```scala mdoc:silent
def dpopClient(http: Client[IO]) =
  for
    key    <- Dpop.Key.generate[IO]
    random <- SecureRandom.javaSecuritySecureRandom[IO]
    oauth   = OAuthClient[IO](http, random, trusted = Set(issuer), dpop = Some(key))
    signed <- Dpop.client[IO](http, key) // adds a proof to every `Authorization: DPoP` request
  yield (oauth, signed)
```

`OAuthClient` sends `dpop_jkt` with the authorization request and a proof with each token request (retrying once on `use_dpop_nonce`). `StepUpClient` sends a `token_type: DPoP` token as `Authorization: DPoP`. Give `signed` to `McpHttpClient.transport`.

---

## 8. Machines and enterprises: the `ext-auth` extensions

### OAuth Client Credentials

A machine client, registered out of band, gets its own tokens. It authenticates with a signed assertion rather than a secret:

```scala mdoc:silent
def machine(http: Client[IO], server: AuthorizationServerMetadata, key: java.security.PrivateKey) =
  for
    authorize <- IO.fromEither(ClientCredentials.authorizer[IO](http, server, ClientAuth.PrivateKeyJwt("reports-bot", key, keyId = Some("bot-2026")), resource.resource))
    stepUp    <- StepUpClient.create[IO](authorize, allowedScopes = Some(Set("mcp:read", "mcp:export")))
  yield stepUp
```

Before anything is sent, the authorization server must list `private_key_jwt` and the key's algorithm (the extension's MUSTs). The assertion lives one minute, names the issuer as its audience, and carries a random `jti`.

### Enterprise-Managed Authorization

The user signs in once to the company IdP, and the IdP's administrators decide which MCP servers each user and client may reach:

1. **Token exchange at the IdP** (RFC 8693): the user's ID token for an ID-JAG addressed to the MCP server's authorization server, naming the MCP resource.
2. **JWT-bearer grant at that authorization server** (RFC 7523): the ID-JAG for an access token bound to the MCP resource.

```scala mdoc:silent
def enterprise(http: Client[IO], server: AuthorizationServerMetadata, idToken: IO[String]) =
  val config = EnterpriseAuth.Config(
    idpIssuer = "https://login.example.com",
    idpTokenEndpoint = "https://login.example.com/oauth2/token",
    idpClient = ClientAuth.SecretPost("desktop-app", sys.env("IDP_SECRET")),
    authorizationServer = server,
    asClient = ClientAuth.Public("https://desktop.example.com/client.json"), // a Client ID Metadata Document
    resource = resource.resource
  )
  StepUpClient.create[IO](EnterpriseAuth.authorizer[IO](http, config, idToken.map(EnterpriseAuth.Subject.IdToken(_))))
```

The MCP server needs nothing new: the access token is an ordinary one. A Scala authorization server can check incoming ID-JAGs with `EnterpriseAuth.verifyIdJag` (type `oauth-id-jag+jwt`, a trusted IdP, `aud` its own issuer, `client_id` the authenticated client, `resource` one it serves, single use).

Keycloak 26.7 does not issue ID-JAGs or advertise the grant profile, so this flow is tested against an in-memory IdP and authorization server (`EnterpriseSuite`).

### Client ID Metadata Documents

A client can publish its registration at an `https` URL that is its `client_id`, so authorization servers that support it need no registration step and no shared secret. `ClientMetadataDocument(...).validate` refuses shared-secret methods and non-https IDs, `ClientMetadataDocument.routes` serves it, and `OAuthClient.clientId` uses it only where the server advertises `client_id_metadata_document_supported`.

---

## 9. Keycloak

Keycloak does not act on the RFC 8707 `resource` parameter. A token's `aud` comes from the realm's **Audience mappers**, so each MCP client scope has one that adds the MCP resource URI. Then Keycloak's tokens validate like any other issuer's: `TokenValidator.Config(issuer, resource)` with the realm URL as issuer, and the default profile accepts Keycloak's `typ: Bearer` while refusing its ID and refresh tokens.

The `iron-mcp` realm is code: `modules/auth/keycloak/iron-mcp-realm.json`, applied with

```bash
KC_URL=https://danarch.tail09c16c.ts.net:10000 KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=… \
MCP_RESOURCE=https://reports.example.com/mcp scripts/keycloak-realm.sh --test-user alice
```

It creates the realm (ES256 signing keys, 5-minute access tokens, refresh token rotation, brute-force protection, events on), the `mcp:read`, `mcp:export` and `mcp:admin` scopes with their audience mappers, and four clients:

| Client | For | How it authenticates |
| :--- | :--- | :--- |
| `iron-mcp-public` | users signing in through an MCP client | public, PKCE S256, loopback redirects, consent, DPoP-bound tokens |
| `iron-mcp-machine` | the Client Credentials extension | `private_key_jwt` (ES256), DPoP-bound tokens |
| `iron-mcp-ci` | `KeycloakIntegrationSuite` | client secret, printed once by the script |
| `iron-mcp-introspector` | the MCP server's RFC 7662 calls | client secret |

No secret is in the repository. The script prints the `IRON_MCP_KC_*` lines that turn on the live suite.

---

## 10. The client: discovery, PKCE, refresh, step-up

`OAuthClient` is the client side, from the first `401` to a token:

1. **Discovery.** `discover(rejection, resource)` reads `resource_metadata` from the challenge (which must be on the resource's origin), fetches the RFC 9728 document, checks it names the resource that was called, then fetches the authorization server's RFC 8414 metadata (or OpenID configuration) and checks its `issuer`. Every URL must be `https`, loopback excepted.
2. **Trust.** Only an authorization server on the client's allowlist is ever contacted. The resource server names it, and a client that follows any name can be sent to an attacker (the class of bug in GHSA-6qxp-vccf-f47h).
3. **Authorization code with PKCE S256.** A server that does not advertise `S256` is refused before the user is sent anywhere. Every request carries the RFC 8707 `resource`. The redirect's `state` must match, and its `iss` too (RFC 9207) whenever the server says it sends one.
4. **Refresh.** `authorizer` refreshes when the requested scopes are already granted. A refresh cannot widen a grant, so a step-up goes back to the user.

`authorizer` has exactly the shape `StepUpClient.create` takes:

```scala mdoc:silent
def connect(http: Client[IO], rejection: ironmcp.client.McpClientError.Http, openBrowser: Uri => IO[AuthorizationResponse]) =
  for
    random    <- SecureRandom.javaSecuritySecureRandom[IO]
    oauth      = OAuthClient[IO](http, random, trusted = Set(issuer))
    found     <- oauth.discover(rejection, resource = resource.resource)
    authorize <- oauth.authorizer(found, ClientAuth.Public("iron-mcp-public"), redirectUri = "http://127.0.0.1:7777/callback", openBrowser)
    stepUp    <- StepUpClient.create[IO](authorize, allowedScopes = Some(Set("mcp:read", "mcp:export")))
  yield stepUp
```

`openBrowser` opens the URL and returns what the redirect brought back; `AuthorizationResponse.fromRedirect(uri)` reads `code`, `state` and `iss` from it.

### The Scope-Union Rule (SEP-2350)

On `403 insufficient_scope`, `StepUpClient` asks for everything it asked for before **plus** the challenged scopes, then retries:

$$\text{requested}_{\text{new}} = \text{requested}_{\text{previous}} \cup \text{challenged}$$

Asking for the missing scope alone (`ScopeStrategy.Naive`) replaces the token with one that lacks the scopes that already worked, so the retry fails and tools that worked a moment ago start failing too:

![Naive Anti-Pattern](../images/naive_antipattern.svg)

If the authorization server grants less than was asked (the user or a policy declined), `StepUpClient` stops with `NarrowerGrantException` instead of looping. With `allowedScopes`, a challenge naming anything else is refused (`ScopeNotAllowed`), so a hostile server cannot widen what the client asks for. A token about to expire is renewed before it is sent.
