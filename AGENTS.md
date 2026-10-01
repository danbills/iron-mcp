# Repository Guidelines

## Project Structure & Module Organization

iron-mcp is an MCP server for Scala 3 (protocol revision 2026-07-28), cross-built to the JVM and Scala Native with no reflection.

- `modules/core/src/main/` — library source (`coreJVM` / `coreNative`)
  - `protocol/` — spec types: `JsonRpc.scala`, `Meta.scala`, one file per capability, `Refined.scala` (Iron aliases from the spec)
  - `schema/` — compile-time macro deriving JSON Schema from Iron types
  - `server/` — `Providers.scala`, `McpServer.scala` (stateless dispatch)
  - `transport/` — `Wire.scala`, `Stdio.scala`
- `modules/core/src/test/` — munit test suites
- `modules/demo/` — runnable stdio demo server
- `build.sbt`, `project/` — sbt 2 build

## Build, Test, and Development Commands

```bash
sbt --client "coreJVM/Test/testFull"   # run tests
sbt --client "demoJVM/run"             # start the stdio demo server on the JVM
sbt --client "demoNative/nativeLink"   # native binary
```

Releases are cut locally (no CI): `git tag -a v0.1.0`, then `sbt +publishSigned` and `sbt sonaUpload`; the version comes from the tag via sbt-dynver.

## Coding Style & Naming Conventions

- Scala 3.9.0-RC6, significant indentation (no braces).
- Strict compiler flags (`-Wunused:all -Wvalue-discard -deprecation -feature -unchecked`); code must compile warning-free.
- No reflection, no Jackson, no hand-written JSON Schema: codecs derive at compile time from `Mirror` (Circe); hand-write one only where derivation cannot express the shape.
- Express constraints as Iron refinement types (`String :| (Not[Empty] DescribedAs "...")`); the schema macro reads them. `DescribedAs` goes on the outermost type only.
- Names follow domain vocabulary (`McpServer`, `ToolProvider`); one file per concept.

## Testing Guidelines

- Framework: munit + munit-cats-effect (`CatsEffectSuite`); existing suites are `ProtocolSuite.scala` and `SchemaSuite.scala`. Name new suites `<Area>Suite` under `modules/core/src/test/scala/ironmcp/`.
- Run with `sbt --client "coreJVM/Test/testFull"`; keep tests fast and hermetic. Cover new protocol behavior by round-tripping real wire JSON through `Wire`.

## Commit & Pull Request Guidelines

Commits use Conventional Commits with imperative subjects (`feat:`, `fix:`, `build:`, `docs:`, `refactor!:` for breaking changes). Keep them specific, e.g. `feat: derive tool input schemas from Iron constraints`.

Pull requests should name the protocol area changed, justify any new hand-written codec, confirm `coreJVM/Test/testFull` and `demoNative/nativeLink` pass, and link the relevant spec section.

## Agent-Specific Instructions

- Never introduce reflection, Jackson, or runtime schema generation — they break the Scala Native and GraalVM story.
- Do not re-enable `LTO.thin` in the Native build (breaks linking); tool failures return `isError: true` results, never JSON-RPC errors.
- `McpServer.handle` must stay a pure function of one message with no per-client state.
