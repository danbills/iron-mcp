# Repository Guidelines

## Project Structure & Module Organization

iron-mcp is an MCP server for Scala 3 (protocol revision 2026-07-28), cross-built to the JVM and Scala Native with no reflection.

- `modules/core/src/main/` — library source (`coreJVM` / `coreNative`)
  - `protocol/` — spec types: `JsonRpc.scala`, `Meta.scala`, one file per capability, `Refined.scala` (Iron aliases from the spec)
  - `schema/` — compile-time macro deriving JSON Schema from Iron types
  - `server/` — `Providers.scala`, `McpServer.scala` (stateless dispatch), `McpTool.scala` (typed tools, `ToolSet`); all polymorphic in `F[_]`
  - `transport/` — `Wire.scala`, `Stdio.scala`
- `modules/core/src/test/` — munit test suites
- `modules/demo/` — runnable stdio demo server
- `modules/weather/` — example MCP server over the NWS (weather.gov) API, JVM-only: `Nws.scala` (models + `NwsOp` algebra + `Nws[G]` smart constructors), `NwsHttp4s.scala` (interpreter), `WeatherTools.scala` (tools as `Free` programs), `Main.scala` (wiring)
- `modules/nasa/` — example MCP server over NASA's APOD API (`NASA_API_KEY`), JVM-only, same layout (`ApodOp`, `ApodHttp4s`, `ApodTools`)
- `build.sbt`, `project/` — sbt 2 build

## Build, Test, and Development Commands

```bash
sbt "coreJVM/Test/testFull"   # run tests
sbt "demoJVM/run"             # start the stdio demo server on the JVM
sbt "weather/run"             # run the weather.gov example (needs no key)
sbt "nasa/run"                # run the NASA APOD example (set NASA_API_KEY)
sbt "demoNative/nativeLink"   # native binary
```

Releases are cut locally (no CI): `git tag -a v0.1.0`, then `sbt +publishSigned` and `sbt sonaUpload`; the version comes from the tag via sbt-dynver.

## Coding Style & Naming Conventions

- Scala 3.9.0-RC6, significant indentation (no braces).
- Strict compiler flags (`-Wunused:all -Wvalue-discard -deprecation -feature -unchecked`); code must compile warning-free.
- No reflection, no Jackson, no hand-written JSON Schema: codecs derive at compile time from `Mirror` (Circe); hand-write one only where derivation cannot express the shape.
- Express constraints as Iron refinement types (`String :| (Not[Empty] DescribedAs "...")`); the schema macro reads them. `DescribedAs` goes on the outermost type only.
- Names follow domain vocabulary (`McpServer`, `ToolProvider`); one file per concept.

## Testing Guidelines

- Framework: munit + munit-cats-effect (`CatsEffectSuite`). Core suites are `ProtocolSuite.scala` and `SchemaSuite.scala` under `modules/core/src/test/scala/ironmcp/`; the examples have `WeatherToolsSuite` and `ApodToolsSuite`. Name new suites `<Area>Suite`.
- Example tools are tested offline: interpret their programs with a canned `Op ~> Either[Throwable, *]`, and test the http4s interpreter against `Client.fromHttpApp`. No test may hit the network.
- Run with `sbt "coreJVM/Test/testFull"`; keep tests fast and hermetic. Cover new protocol behavior by round-tripping real wire JSON through `Wire`.

## Commit & Pull Request Guidelines

Commits use Conventional Commits with imperative subjects (`feat:`, `fix:`, `build:`, `docs:`, `refactor!:` for breaking changes). Keep them specific, e.g. `feat: derive tool input schemas from Iron constraints`.

Pull requests should name the protocol area changed, justify any new hand-written codec, confirm `coreJVM/Test/testFull` and `demoNative/nativeLink` pass, and link the relevant spec section.

## sbt 2 Behavior That Bites

- **`test` is incremental**: it runs only suites affected by changed code (old `testQuick`). A short or empty run is not a pass. Use `Test/testFull`.
- **Compile results are cached on disk, across directories and worktrees**, and `clean` does not evict them. A cache hit prints `[success]` in ~0 s and **does not replay warnings**, so "no warnings" after a fast compile proves nothing. To see the real warnings, change an input (e.g. append a comment to any source, then revert it) and run `clean; compile`. `main` itself carries ~14 pre-existing `-Wunused` warnings; compare against that baseline, not zero.
- **`sbt --client` joins its arguments into one command line**: chain with `;` inside one quoted argument (`sbt --client "a; b"`). Plain `sbt -batch` accepts the same form.
- **JDK updated in place** makes the running server fail to spawn processes (`Cannot run program "/usr/bin/clang": Failed to exec spawn helper`), which breaks `nativeLink`. Run `sbt --client shutdown` and retry.
- **`export <proj>/Runtime/fullClasspath` prints virtual paths** (`${OUT}`, `${CSR_CACHE}`, `>sha256-…` suffixes). Map `${OUT}` → `target/out`, `${CSR_CACHE}` → `~/.cache/coursier/v1`, and strip the suffix to get a `java -cp` classpath. That is the way to drive a stdio server by hand, since `sbt run` does not forward stdin.
- **Scala 3.9.0 crashes** (`assertion failed: unexpected tree for type application`) on a second type-parameter clause combined with named/default arguments. `McpTool.apply` uses a `Builder` for that reason; do not "simplify" it into clause interleaving.

## Agent-Specific Instructions

- Never introduce reflection, Jackson, or runtime schema generation — they break the Scala Native and GraalVM story.
- Do not re-enable `LTO.thin` in the Native build (breaks linking); tool failures return `isError: true` results, never JSON-RPC errors.
- `McpServer.handle` must stay a pure function of one message with no per-client state.
- Keep core free of `IO` and of cats-free: ask for the weakest typeclass that works (`Applicative` for dispatch, `ApplicativeThrow` for tools, `Async` + `LiftIO` only in `Stdio`).
- Outbound calls in example tools go through an algebra and interpreter, not inline http4s in the handler.
