# Repository Guidelines

## Project Structure & Module Organization

iron-mcp is an MCP server for Scala 3 (protocol revision 2026-07-28), cross-built to the JVM and Scala Native with no reflection. The wire types also build for Scala.js, for MCP Apps Views.

- `modules/protocol/src/main/` — `iron-mcp-protocol`, cross-built (`protocolJVM` / `protocolNative` / `protocolJS`), circe and Iron only; also `ToolOutput`, shared by server tools and View tools (core re-exports it as `ironmcp.server.ToolOutput`)
  - `protocol/` — spec types: `JsonRpc.scala`, `Meta.scala`, one file per capability, `Refined.scala` (Iron aliases from the spec)
  - `schema/` — compile-time macro deriving JSON Schema from Iron types (`SchemaSuite`, `SumSchemaSuite` run on all three platforms)
- `modules/core/src/main/` — library source (`coreJVM` / `coreNative`), depends on `protocol`
  - `server/` — `Providers.scala`, `McpServer.scala` (stateless dispatch), `McpTool.scala` (typed tools, `ToolSet`; `McpTool.reporting` for progress), `Emit.scala` (progress notifications through a per-request `NotificationSink`); all polymorphic in `F[_]`
  - `server/Interaction.scala` — `McpTool.interactive` support: `ToolContext`, `Elicit`, `RequestStateCodec` (HMAC-sealed `requestState`; `HmacSha256.scala` is plain Scala because Scala Native has no `javax.crypto`)
  - `transport/` — `Wire.scala`, `Stdio.scala`
  - `client/` — `McpClient.scala` (typed calls, `callToolGathering`, `InputHandlers`), `ClientTransport.scala` (`inProcess`, `lines`), `StdioClient.scala` (child processes), `McpClientError.scala`
- `modules/http/` — `iron-mcp-http`, JVM-only: `McpHttp` (server; opt-in CORS for browser hosts with `Config(cors = true)`), `McpHttpClient` (client), `McpHeaders` (the mirrored headers and their base64 rule)
- `modules/otel/` — `iron-mcp-otel`, JVM-only opt-in module: OpenTelemetry instrumentation (`OtelServer`, `OtelHttp`, `OtelStdio`, `OtelClient`, `McpTelemetry`, `TraceContext`, `ErrorLadder`, `CostHistogram`) adhering to MCP GenAI semantic conventions, Section 10.2 error ladder and cost histograms
- `modules/auth/` — `iron-mcp-auth`, JVM-only opt-in module, **secure by default**: `McpAuthServer` (resource server: `routesFor`, `routesForOptional` for public tools, audit hook), `ResourceIdentity` (one public URL → resource, audience, `resource_metadata`, mount path), `TokenValidator` (`jwks`/`sharedSecret`/`layered`, issuer and audience required, `exp` required, `typ` profile, algorithm allowlist; `withRevocation`), `Jws`/`Jwt`/`Jwks` (RS/PS/ES/EdDSA, JWK checks, `JwksProvider.remote` with single-flight fetch and refetch cooldown), `AuthPolicy` + `Access` (deny by default; `.requires`/`.signedIn`/`.public` on tools write `_meta["io.github.danbills/auth"]`), `ScopedTools`/`ScopedResources`/`ScopedPrompts`, `AuthChallenge` (escaped `WWW-Authenticate` build and parse), `RevocationList`, `Introspection` (RFC 7662), `AuthAudit`, `ClientAuth` (`private_key_jwt`, secrets) and `OAuthEndpoint`, `OAuthClient`, `StepUpClient`, `KeycloakClient`; `keycloak/iron-mcp-realm.json` + `scripts/keycloak-realm.sh` (realm as code)
- `modules/otel-auth/` — `iron-mcp-otel-auth`: `OtelAuth.audit`, auth decisions as a counter and span events
- `modules/tasks/` — `iron-mcp-tasks`, cross-built (`tasksJVM` / `tasksNative`): Tasks extension (`io.modelcontextprotocol/tasks`, SEP-2663, Handbook §7.3) with `TaskStore`, `TaskServer` background fiber management and cooperative cancellation, and `TasksClient` with transparent polling (`callToolWithPolling`)
- `modules/apps/` — `iron-mcp-apps`, cross-built (`appsJVM` / `appsNative`): MCP Apps (`io.modelcontextprotocol/ui`, Handbook §7.4): `McpApps` (extension id, `text/html;profile=mcp-app`, `clientSupports`), `withUi` / `withVisibility` on `McpTool` (`_meta.ui.resourceUri`, `Visibility`), `UiResources` serving `ui://` Views with their `_meta.ui` (`UiCsp`, `UiPermissions`), and `UiHtml.inlineScript` / `inlineStyle` (a bundled View inlined into its page)
- `modules/apps-view/` — `iron-mcp-apps-view`, Scala.js only (`appsView`): the View side of MCP Apps (ext-apps protocol `2026-01-26`, JSON-RPC over `postMessage` with the host). `McpAppView` (handshake, `events` stream, `hostContext` signal, server tools and resources through the host, sampling, cancellation both ways), `ViewTool` / `ViewTools` (tools the View offers the host: `tools/list`, `tools/call`, `list_changed`), `HostPort` (`windowParent`, `inMemory` for tests), `HostTheming`, `SafeArea`, `AutoResize` (measures as ext-apps' `App`); cats-effect and fs2, polymorphic in `F`
- `modules/docs/` — mdoc documentation runner verifying markdown guides against compiled modules
- `modules/core/src/test/` — munit test suites
- `modules/demo/` — runnable stdio demo server
- `modules/weather/` — example MCP server over the NWS (weather.gov) API, JVM-only: `Nws.scala` (models + `NwsOp` algebra + `Nws[G]` smart constructors), `NwsHttp4s.scala` (interpreter), `WeatherTools.scala` (tools as `Free` programs), `Main.scala` (wiring)
- `modules/nasa/` — example MCP server over NASA's APOD API (`NASA_API_KEY`), JVM-only, same layout (`ApodOp`, `ApodHttp4s`, `ApodTools`)
- `build.sbt`, `project/` — sbt 2 build; `project/Mcpb.scala` writes `.mcpb` bundles (hand-written manifest JSON and zip Unix modes, since the build has no JSON or zip library)

## Build, Test, and Development Commands

```bash
sbt "coreJVM/Test/testFull"   # run tests
sbt "http/Test/testFull"      # HTTP transport tests (in memory, no sockets)
sbt "otel/Test/testFull"      # OpenTelemetry instrumentation tests
sbt "auth/Test/testFull"      # authorization tests (Keycloak suite runs when IRON_MCP_KC_* are set)
sbt "tasksJVM/Test/testFull"  # Tasks extension tests (JVM)
sbt "tasksNative/Test/testFull" # Tasks extension tests (Scala Native)
sbt "protocolJVM/Test/testFull; protocolNative/Test/testFull; protocolJS/Test/testFull" # wire types and schema, all platforms
sbt "appsJVM/Test/testFull"   # MCP Apps tests (also appsNative)
sbt "appsView/Test/testFull"  # MCP Apps View (Scala.js; runs on Node)
sbt "docs/run"               # compile and verify markdown docs with mdoc
sbt "demoJVM/run"             # start the stdio demo server on the JVM
sbt "weather/run"             # run the weather.gov example (needs no key)
sbt "nasa/run"                # run the NASA APOD example (set NASA_API_KEY)
sbt "demoNative/nativeLink"   # native binary
sbt "demoNative/mcpb"         # pack it as an MCP Bundle (project/Mcpb.scala; checks tools against the binary)
```

Releases are cut locally (no CI): `git tag -a v0.1.0`, then `sbt +publishSigned` and `sbt sonaUpload`; the version comes from the tag via sbt-dynver.

## Coding Style & Naming Conventions

- Scala 3.9.0, significant indentation (no braces).
- Strict compiler flags (`-Wunused:all -Wvalue-discard -deprecation -feature -unchecked -Werror`): any warning fails the build, in every module, tests and Scala Native included. Shared JVM/Native code must be clean on both; prefer `@annotation.unused` to `@nowarn`, which warns on the platform where it suppresses nothing.
- No reflection, no Jackson, no hand-written JSON Schema: codecs derive at compile time from `Mirror` (Circe); hand-write one only where derivation cannot express the shape.
- Express constraints as Iron refinement types (`String :| (Not[Empty] DescribedAs "...")`); the schema macro reads them. `DescribedAs` goes on the outermost type only.
- Names follow domain vocabulary (`McpServer`, `ToolProvider`); one file per concept.

## Testing Guidelines

- Framework: munit + munit-cats-effect (`CatsEffectSuite`). Core suites are `ProtocolSuite.scala` under `modules/core/src/test/scala/ironmcp/` and `SchemaSuite.scala` under `modules/protocol/src/test/scala/ironmcp/`; the examples have `WeatherToolsSuite` and `ApodToolsSuite`. Name new suites `<Area>Suite`.
- Example tools are tested offline: interpret their programs with a canned `Op ~> Either[Throwable, *]`, and test the http4s interpreter against `Client.fromHttpApp`. No test may hit the network.
- Run with `sbt "coreJVM/Test/testFull"`; keep tests fast and hermetic. Cover new protocol behavior by round-tripping real wire JSON through `Wire`.

## Commit & Pull Request Guidelines

Commits use Conventional Commits with imperative subjects (`feat:`, `fix:`, `build:`, `docs:`, `refactor!:` for breaking changes). Keep them specific, e.g. `feat: derive tool input schemas from Iron constraints`.

Pull requests should name the protocol area changed, justify any new hand-written codec, confirm `coreJVM/Test/testFull` and `demoNative/nativeLink` pass, and link the relevant spec section.

## sbt 2 Behavior That Bites

- **`test` is incremental**: it runs only suites affected by changed code (old `testQuick`). A short or empty run is not a pass. Use `Test/testFull`.
- **Compile results are cached on disk, across directories and worktrees**, and `clean` does not evict them. A cache hit prints `[success]` in ~0 s and does not replay warnings. Under `-Werror` that is safe (a cached success was compiled clean), but compile the Native side too (`coreNative/Test/compile; demoNative/compile`): a warning can exist on one platform only.
- **`sbt --client` joins its arguments into one command line**: chain with `;` inside one quoted argument (`sbt --client "a; b"`). Plain `sbt -batch` accepts the same form.
- **JDK updated in place** makes the running server fail to spawn processes (`Cannot run program "/usr/bin/clang": Failed to exec spawn helper`), which breaks `nativeLink`. Run `sbt --client shutdown` and retry.
- **`export <proj>/Runtime/fullClasspath` prints virtual paths** (`${OUT}`, `${CSR_CACHE}`, `>sha256-…` suffixes). Map `${OUT}` → `target/out`, `${CSR_CACHE}` → `~/.cache/coursier/v1`, and strip the suffix to get a `java -cp` classpath. That is the way to drive a stdio server by hand, since `sbt run` does not forward stdin.
- **Scala 3.9.0 crashes** (`assertion failed: unexpected tree for type application`) on a second type-parameter clause combined with named/default arguments. `McpTool.apply` uses a `Builder` for that reason; do not "simplify" it into clause interleaving.

## Agent-Specific Instructions

- Never introduce reflection, Jackson, or runtime schema generation — they break the Scala Native and GraalVM story.
- Do not re-enable `LTO.thin` in the Native build (breaks linking); tool failures return `isError: true` results, never JSON-RPC errors.
- `McpServer.handle` must stay a pure function of one message with no per-client state. Notifications a request emits (progress) go to a sink the transport passes in (`handleEmitting`), never to state on the server. No log notifications: 2026-07-28 deprecates `logging`. Per-request context (auth claims) comes in through `McpHttp.routesFor`, which builds a server per request.
- Auth stays fail-closed: no `Option` escape hatch on issuer or audience, no default `Access` for an unlisted target, no echo of token contents in `WWW-Authenticate`, no token in an `AuthEvent`.
- Never trust `requestState` unsealed: interactive tools take a `RequestStateCodec`; do not add a default `given` for `unprotected`.
- Only `protocol` and `apps-view` build for Scala.js. Keep `protocol` to circe and Iron (no cats-effect, no `java.io`), and its regexes JS-compatible (no lookbehind, possessive quantifiers or `\p{}`).
- Keep core free of `IO` and of cats-free: ask for the weakest typeclass that works (`Applicative` for dispatch, `ApplicativeThrow` for tools, `MonadThrow` for `McpClient`, `Concurrent` for `ClientTransport.lines`, `Async` only for real stdio and process spawning). On Scala Native, `fs2.io.stdin/stdout/stderr` go through epoll, which refuses regular files: use blocking `System.in/out/err` (see `Stdio.serve`, `StdioClient`).
- Outbound calls in example tools go through an algebra and interpreter, not inline http4s in the handler.
