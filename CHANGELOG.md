# Changelog

All notable changes to iron-mcp. Versions follow [Semantic Versioning](https://semver.org); before 1.0, a minor version may break the API, and such changes are listed under **Breaking**. Artifacts are on Maven Central as `io.github.danbills:iron-mcp-*`.

## [Unreleased]

### Fixed
- An error reply to a message the server could not read (a parse error, an invalid request) had no `id` at all: `Wire.encode` drops null fields, and took the `"id": null` that JSON-RPC 2.0 requires with them. Every transport now sends `"id": null` there.
- On Scala Native, a `StdioClient` whose server process had exited was killed by SIGPIPE on its next write. It now ignores SIGPIPE, as the JVM does, and the call fails with `McpClientError.Closed`.

## [0.8.0] — 2026-11-07

Exceptions a server turns into `-32603 internal error` are no longer lost: they go to a hook, and to the OpenTelemetry span.

### Breaking
- New parameters change the binary signatures of `McpServer`, `TaskServer`, `TaskServer.apply`, `McpHttp.serve` and `McpHttpClient.ember`. Source stays compatible: each new parameter has a default.

### Added
- **`OnUnhandled`** (core): `McpServer(onUnhandled = ...)` and `TaskServer(onUnhandled = ...)` receive the message and the exception whenever a handler throws something other than an `McpException`. The client still gets `-32603` with no detail; a failing hook does not lose the reply.
- **`UnhandledLog(logger)`** (http): an `OnUnhandled` that logs to a log4cats `Logger`: method, request id and stack trace, never the params.
- `logger` on `McpHttp.serve` and `McpHttpClient.ember`: Ember's own log goes to a log4cats `Logger` you pass, or nowhere (the default). `iron-mcp-http` now declares `log4cats-core` directly; http4s already brought it in.

### Fixed
- `OtelServer` never recorded exceptions on the path the transports use: it called the server's recovering handler, which had already turned the exception into a reply. The exception is now on the span (`recordException`, error status) before it becomes `-32603`.
- A background task that threw stored the exception's message as its error, where `tasks/get` showed it to the client. It now fails with `-32603 internal error`, and the exception goes to `onUnhandled`.
- Test runs no longer print SLF4J's "Failed to load class StaticLoggerBinder" warning (`slf4j-nop` in test scope only; published POMs leave the logging backend to the application).

## [0.7.0] — 2026-11-07

Both `ext-auth` extensions (OAuth Client Credentials, Enterprise-Managed Authorization) run end to end against a live Keycloak 26.7.

### Added
- **`IdJagIssuer`** (auth): the enterprise IdP's half of Enterprise-Managed Authorization. RFC 8693 token exchange from an SSO ID token to an ID-JAG, decided by deny-by-default `Policy.rules`: by subject, group, verified email domain or predicate; scope caps; `acr` and `maxAuthAge` requirements answered with `insufficient_user_authentication` (RFC 9470). Every policy refusal reads the same. It serves its JWKS and RFC 8414 metadata, and audits every decision without the token.
- **`ClientRegistry` / `ClientCredential`** (auth): client authentication for token endpoints written in Scala. Secrets are kept as SHA-256, compared in constant time, and refused under 32 characters; `private_key_jwt` assertions are checked against a replay cache; one authentication method per request.
- **`EnterpriseAuth.checkIdJag`**: the client checks an ID-JAG's type, issuer, audience, resource and expiry before handing it on (IdP mix-up defence). Also `samlToRefreshToken` for SAML sign-in, and `Config.discovered` to build the config from the MCP server's metadata.
- **`Pem`**: PKCS #8 and SubjectPublicKeyInfo keys from PEM text or files.
- `backdate` on `IdJagIssuer.Config` and `ClientAuth.PrivateKeyJwt` (5 s by default): Keycloak refuses any `iat` in its future, whatever clock skew it is configured with.
- Keycloak as code: an RFC 8707 resource client in the `iron-mcp` realm (with `--features=resource-indicators`, `aud` narrows to exactly the MCP URL), and `scripts/keycloak-enterprise.sh` (corp SSO realm, ID-JAG trust pinned to ES256, machine JWKS).
- `KeycloakEnterpriseSuite` (live, opt-in through `IRON_MCP_KC_*`) and `IdJagIssuerSuite`.

### Fixed
- `AccessTokenProfile.keycloak` refused DPoP-bound Keycloak tokens, whose payload `typ` is `DPoP`. It now accepts them when they carry `cnf.jkt`.

## [0.6.0] — 2026-11-06

### Breaking
- **Secure-by-default resource server.** `TokenValidator.Config` takes an issuer and an audience, with no `Option`. Tokens without `exp` are refused. The token-type profile refuses ID and refresh tokens. Algorithms come from an allowlist. `AuthPolicy` denies any tool, resource or prompt it does not list.

### Added
- DPoP (RFC 9449) on both sides: `Dpop` proofs, `DpopPolicy`, `ReplayCache`, client middleware, DPoP-bound tokens.
- The `ext-auth` extensions on the client side: `ClientCredentials` and `EnterpriseAuth` (ID-JAG), `ClientMetadataDocument`.
- A hardened `OAuthClient`: RFC 9207 `iss` check, https only. Also `StepUpClient` with `allowedScopes` and renewal.
- `SessionTools` (`whoami`, `unlock_*`, `sign_out`), the View auth kit (`ViewAuth`), and `DownstreamAuth` + `TokenVault` for third-party tokens through URL elicitation, never passing the MCP token through.
- `AuthPolicy.views()` opens `ui://` Views to hosts before sign-in.

## [0.5.0] — 2026-11-06

### Breaking
- `McpTool.invoke` takes the whole `CallToolParams` and may return `InputRequiredResult`, for `McpTool.interactive` tools that ask the client for input (MRTR elicitation). `requestState` is sealed with HMAC-SHA256 and needs a `RequestStateCodec` in scope.

### Added
- Transports and clients: Streamable HTTP transport (`iron-mcp-http`) and client, `McpClient`, opt-in CORS for host pages.
- MCP Apps: `iron-mcp-apps` (`withUi`, `UiResources`, `UiPermissions`, `UiHtml.inlineStyle`) and `iron-mcp-apps-view` for Views in Scala.js (View tools, `resources/list`, sampling, cancellation, `AutoResize`).
- Tasks extension (SEP-2663): `TaskServer`, `TasksClient` polling, cooperative cancellation.
- OpenTelemetry instrumentation (`iron-mcp-otel`) following the MCP GenAI semantic conventions.
- Auth: OAuth 2.1 resource server, the step-up Scope-Union Rule, Keycloak integration, client OAuth flow with discovery, PKCE and refresh, and hiding tools the token cannot call.
- Progress from running tools over stdio and SSE; sampling and roots in multi-round-trip tools; URL-mode elicitation (`Elicit.url`); `ResourceProvider.all`; a `Format["..."]` schema annotation; the Native demo as an MCP Bundle (`.mcpb`).

### Fixed
- An unknown tool is answered with `-32602`, not an `isError` result.
- A failing provider gets an error response instead of ending the stream.
- Task IDs come from `SecureRandom`.
- Tool properties keep their declared order; derived schema keywords escape control characters.

## [0.4.0] — 2026-11-04

### Added
- Schemas for sum types, opaque `RefinedType`s, and OpenAI strict mode.

## [0.3.0] — 2026-10-08

### Breaking
- `McpTool.apply` no longer takes `outputSchema`: the output schema is derived from the handler's result type.

### Added
- Output schemas and `structuredContent` from the result type; `ConfiguredJsonSchemaOf` for circe `Configuration` naming.

## [0.2.0] — 2026-10-03

### Breaking
- Core no longer hard-wires `IO`: `McpServer[F]`, the providers, `McpTool[F]` and `ToolSet[F]` ask for the weakest typeclass they use, and `McpTool[A](...)` goes through a `Builder` so `F` is inferred from the handler. A failing handler becomes an `isError` result.

### Added
- Example servers over weather.gov and NASA APOD.

## [0.1.0] — 2026-08-19

First release: the MCP 2026-07-28 protocol on a cross-platform Scala 3 core (JVM and Scala Native), with JSON Schema derived at compile time from Iron constraints, and the stdio transport.

[Unreleased]: https://github.com/danbills/iron-mcp/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/danbills/iron-mcp/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/danbills/iron-mcp/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/danbills/iron-mcp/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/danbills/iron-mcp/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/danbills/iron-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/danbills/iron-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/danbills/iron-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/danbills/iron-mcp/releases/tag/v0.1.0
