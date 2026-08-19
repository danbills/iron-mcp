# iron-mcp

A **Model Context Protocol server** for Scala 3 — protocol revision
**2026-07-28**, written from the specification with no Java SDK, no Jackson,
and no reflection anywhere. Cross-builds to the JVM and **Scala Native**.

```scala
val tools = new ToolProvider:
  def list(params: ListToolsParams): IO[ListToolsResult] = ...
  def call(params: CallToolParams): IO[CallToolResult | InputRequiredResult] =
    IO.pure(CallToolResult.text("hello"))

val server = McpServer(
  info  = Implementation(name = "my-server", version = "0.1.0"),
  tools = Some(tools)
)

Stdio.serve(server)
```

## Why not the official Java SDK

Two reasons, in order of weight:

1. **It cannot speak the current revision.** MCP Java SDK 2.0.1 implements
   2025-11-25. The current specification is 2026-07-28, which replaced the
   `initialize` handshake with `server/discover` and a required `_meta`
   envelope. Claude Code already negotiates the new revision and falls back to
   the legacy handshake only when a server gives it no choice.
2. **Jackson deserializes reflectively**, which rules out Scala Native and
   forces reachability metadata under GraalVM `native-image`.

Every codec here is a compile-time Circe derivation from a `Mirror`.

## What 2026-07-28 changes

| | 2025-11-25 | 2026-07-28 |
|---|---|---|
| Lifecycle | `initialize` handshake, then `notifications/initialized` | `server/discover`, cacheable, no handshake |
| Version negotiation | in the handshake | required `_meta` envelope on **every** request |
| Streaming | SSE sessions | `subscriptions/listen` returning a subscription id |
| Errors | JSON-RPC five | plus `-32020` header mismatch, `-32021` missing client capability, `-32022` unsupported version |
| Long operations | tasks | `input_required` results |

The revision is stateless by construction, which is why `McpServer.handle` is a
pure function of one message and holds no per-client state at all.

## Types that do real work

**Iron constraints come from the specification text**, not from taste. The
`_meta` key grammar — optional dot-separated prefix, alphanumeric-bounded name —
is a regex in the spec prose and a type here, so a malformed key cannot be
written in Scala and will not decode off the wire.

```scala
type MetaKey   = String :| MetaKeyC
type ToolName  = String :| Match["^[a-zA-Z0-9_-]{1,128}$"]
type MetaObject = Map[MetaKey, Json]
```

**Capabilities are derived from providers.** You do not declare them:

```scala
val capabilities = ServerCapabilities(
  tools     = tools.as(ToolsCapability(...)),
  resources = resources.as(ResourcesCapability(...)),
  ...
)
```

A server therefore cannot advertise a capability it has no way to serve.

**`input_required` is only reachable where the spec allows it.** The three
methods that may ask the client for something return a union; nothing else can,
because no other signature admits it:

```scala
def call(params: CallToolParams): IO[CallToolResult | InputRequiredResult]
def get(params: GetPromptParams): IO[GetPromptResult | InputRequiredResult]
def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult]
```

**Tool failures are results, not protocol errors.** MCP reserves JSON-RPC errors
for protocol failures; a tool that fails at its job returns `isError: true` so
the model can read and correct it. An Iron violation lands there too:

```json
{"id":4,"result":{"content":[{"type":"text","text":"Should be included in [1, 10]"}],"isError":true}}
```

## Codecs

Everything derives. Exactly four codecs are hand-written, and only where
derivation cannot express the shape:

| Type | Why |
|---|---|
| `JsonRpcMessage` | discriminated by field *presence*: `id`+`method` is a request, `method` alone a notification, `result` xor `error` a response |
| `ContentBlock` | union tagged by a `type` field |
| `ResourceContents` | `text` xor `blob`, never both |
| `CompletionReference` | `ref/prompt` vs `ref/resource` |

`Wire.encode` applies `deepDropNullValues`: MCP distinguishes an absent optional
from a null one, and several hosts reject `null` where they expect omission.

## Layout

| Path | Role |
|---|---|
| `protocol/Refined.scala` | Iron aliases lifted from the spec |
| `protocol/JsonRpc.scala` | envelope, request ids, the nine error codes |
| `protocol/Meta.scala` | the `_meta` envelope; `protocolVersion` + `clientCapabilities` required |
| `protocol/{Tools,Resources,Prompts,Completion,Discover,Subscriptions,Notifications}.scala` | the full server surface |
| `server/Providers.scala` | one trait per capability |
| `server/McpServer.scala` | dispatch, version checks, capability derivation |
| `transport/{Wire,Stdio}.scala` | newline-delimited JSON-RPC on stdin/stdout |

## Running

```bash
sbt --client "coreJVM/Test/testFull"   # 17 protocol tests
sbt --client "demoJVM/run"             # stdio server on the JVM
sbt --client "demoNative/nativeLink"   # native binary
```

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{
  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
  "io.modelcontextprotocol/clientCapabilities":{}}}}' \
| ./target/out/native0.5/scala-3.8.3/iron-mcp-demo/native/ironmcp.demo.Main
```

The Native binary is ~17 MB and answers `server/discover` in about 44 ms
including process spawn — which is the point of avoiding reflection: a local
harness spawns this per session.

## Build notes

- **sbt 2** with Scala Native. Both plugins publish under the `_sbt2_3` suffix;
  in sbt 2 cross-built dependencies use plain `%%` — `%%%` and `sbt-platform-deps`
  are gone.
- **`LTO.thin` breaks the Native link** with `undefined reference to
  snFatalErrorPrefix`. Left off.
- **Scala 3.9.0-RC6**, ahead of its release as the next LTS (succeeding 3.3).
  Native works because `nscplugin_3.9.0-RC6` is published for Scala Native
  0.5.12; the compiler must match the Scala version exactly. Clean under
  `-Wunused:all -Wvalue-discard`.

## Stack

Scala 3.9.0-RC6 · Iron 3.3.2 · Circe 0.14.16 · cats-effect 3.7.0 · fs2 3.13.0 ·
Scala Native 0.5.12

## Status

The protocol layer, dispatcher and stdio transport are complete and tested.
Not yet built: real capability providers (email, web search, computer use) and
an HTTP transport.

## License

MIT
