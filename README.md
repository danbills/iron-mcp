# iron-mcp

A **stateless MCP 2.0 server** for Scala 3, built on the official
[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) with
[Iron](https://github.com/Iltotore/iron) refinement types at the protocol
boundary and an [http4s](https://http4s.org) transport.

```scala
val greet = ToolDef(
  name        = "greet",                       // String :| Match["^[a-zA-Z0-9_-]{1,128}$"]
  description = "Greet someone, one to ten times.",
  inputSchema = greetSchema,
  handler     = (args: Greet) => IO.pure(ToolOutcome.Text(s"Hello, ${args.name}!"))
)

IronMcpServer.resource(ServerInfo("my-server", "0.1.0"), List(greet)).use { server =>
  EmberServerBuilder.default[IO].withHttpApp(server.routes.orNotFound).build.useForever
}
```

Your handler receives `Greet`, not a `Json` blob — and `Greet`'s fields carry
their constraints in their types:

```scala
type Repeat = Int :| Interval.Closed[1, 10]
final case class Greet(name: NonEmptyString, times: Repeat) derives Decoder
```

## Why stateless

Stateless Streamable HTTP means no session id, no SSE stream, no server-held
per-client state: one POST carries one JSON-RPC message and gets one response.
Any replica can serve any request, so the server scales horizontally and
restarts without dropping anyone's session.

The SDK's stateless SPI (`McpStatelessServerTransport`) is **two methods wide**,
which is why this library implements it directly on http4s instead of pulling in
`jakarta.servlet` and a servlet container for `HttpServletStatelessServerTransport`.

## Two layers of validation, on purpose

| Layer | Enforces | Sees |
|---|---|---|
| JSON Schema (SDK, `validateToolInputs(true)`) | what the **model was told** about the tool | ranges, required fields, types |
| Iron refinements (Circe decoder) | what your **code actually requires** | everything the schema can express, plus what it can't |

They are not redundant. A schema can say `maximum: 65535`; it cannot say
"non-empty after trimming", "a valid tool name", or any predicate you can write
in Scala. Constraints in both places means the model gets an accurate
description *and* your handler cannot be reached with a value it doesn't accept.

Either way the rejection arrives as a **tool result with `isError: true`**, never
a JSON-RPC error — MCP reserves protocol errors for protocol failures, and a
model can read and correct a tool error:

```json
{"jsonrpc":"2.0","id":3,"result":{
  "content":[{"type":"text","text":"Tool (describe_port) input validation failed: [/port: must have a maximum value of 65535]"}],
  "isError":true}}
```

## Layout

| File | Role |
|---|---|
| `Refined.scala` | Iron aliases: `ToolName`, `NonEmptyString`, `SemVer`, `Port`, `EndpointPath` |
| `ToolDef.scala` | A tool whose arguments decode into a refined `A` before your code runs |
| `Http4sStatelessTransport.scala` | `McpStatelessServerTransport` over http4s; Origin checks, 405 on GET/DELETE |
| `IronMcpServer.scala` | Assembles tools into an `McpStatelessAsyncServer`, exposes `HttpRoutes[IO]` |
| `ReactorInterop.scala` | The entire Reactor↔cats-effect bridge: two functions |
| `demo/` | Two tools and a runnable server on `127.0.0.1:8765` |

## Running

```bash
sbt --client "Test/testFull"   # full protocol suite: handshake, tools, refinement failures
sbt --client run               # demo server on http://127.0.0.1:8765/mcp
```

```bash
curl -sS -X POST http://127.0.0.1:8765/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0.0"}}}'
```

Verified against MCP Java SDK 2.0.1, which negotiates protocol version
**2025-11-25**.

## Notes

- **Jackson serializes the protocol envelope; Circe serializes your side of it.**
  The SDK deserializes its own `McpSchema` Java records reflectively, which Circe
  cannot do generically, so `mcp-json-jackson3` stays. Circe + Iron own tool
  arguments and results — the only place your types appear.
- **The demo binds to loopback.** An MCP server with no auth in front of it has
  no business listening on `0.0.0.0`. `Http4sStatelessTransport` also rejects
  disallowed `Origin` headers (DNS-rebinding protection) when you supply an
  allowlist.

## Stack

Scala 3.8.3 · Iron 3.3.1 · MCP Java SDK 2.0.1 · http4s 0.23.32 · cats-effect 3.7.0 · Circe 0.14.15

## License

MIT
