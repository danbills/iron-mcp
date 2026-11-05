# iron-mcp

A **Model Context Protocol server** for Scala 3 — protocol revision
**2026-07-28**, written from the specification with no Java SDK, no Jackson,
and no reflection anywhere. Cross-builds to the JVM and **Scala Native**.

```scala
type Recipient = String :| (Not[Empty] DescribedAs "Who to greet")
type Repeats   = Int :| (Interval.Closed[1, 10] DescribedAs "How many times")

final case class Greet(name: Recipient, times: Repeats)
  derives Decoder, JsonSchemaOf

final case class Greeting(greeting: String)
  derives Encoder.AsObject, JsonSchemaOf

val greet = McpTool[Greet]("greet", "Greet someone, one to ten times."): args =>
  IO.pure(Greeting(s"Hello, ${args.name}!" * args.times))

val server = McpServer(
  info  = Implementation(name = "my-server", version = "0.1.0"),
  tools = Some(ToolSet.of(greet))
)

Stdio.serve(server)
```

There is no hand-written JSON Schema, because the type already is one:

```json
{"type":"object",
 "properties":{
   "name":  {"type":"string",  "minLength":1, "description":"Who to greet"},
   "times": {"type":"integer", "minimum":1, "maximum":10, "description":"How many times"}},
 "required":["name","times"]}
```

`Not[Empty]` became `minLength: 1`, `Interval.Closed[1, 10]` became the bounds,
and `DescribedAs` became the description.
Widen the type and the advertised schema widens in the same edit — there is no
second copy of the constraints to drift.

The output works the same way. The handler returns a `Greeting`, so the tool
advertises an `outputSchema` derived from `Greeting`, and its result's
`structuredContent` is that `Greeting`, encoded. A `Show` for the type renders the text block;
without one, the text block is the serialized JSON. Return `Either[String, O]`
when the tool can fail at its job (`Left` becomes an `isError` result), or a
`CallToolResult` to build the result by hand, with no output schema. Results
encoded with a circe `Configuration` derive `ConfiguredJsonSchemaOf`, so a
snake_case encoder gets a snake_case schema.

### Enums, opaque types and strict schemas

Sums derive too, and each schema describes what the matching circe codec writes:

| Derive | circe codec | Schema |
|---|---|---|
| `JsonSchemaOf` | `Codec.AsObject` | `oneOf`, one object per case keyed by its name |
| `ConfiguredJsonSchemaOf` | `ConfiguredCodec` with a discriminator | `oneOf`, each case's fields plus `kind: {"const": "Case"}` |
| `ConfiguredEnumJsonSchemaOf` | `ConfiguredEnumCodec` | `{"type": "string", "enum": [...]}` (cases with fields don't compile) |

An opaque Iron `RefinedType` (`object Port extends RefinedType[Int, Interval.Closed[1, 65535]]`)
has the schema of the type it wraps. `JsonSchemaOf.strict(schema)` rewrites a schema
for OpenAI's strict structured outputs: every object closed, every property
required, optional ones nullable, `oneOf` as `anyOf`.

### Asking the user mid-call (elicitation)

In 2026-07-28 a server never sends the client a request. A tool that needs the
user answers `input_required` with the questions embedded, and the client calls
again with the answers (multi round-trip requests). `McpTool.interactive` does
both halves with types:

```scala
final case class Details(
  title: String :| ((Titled["Meeting title"] & MinLength[1]) DescribedAs "Shown on the room display.")
) derives Decoder, JsonSchemaOf

given RequestStateCodec = RequestStateCodec.hmac(key)   // key: at least 32 bytes

val book = McpTool.interactive[BookArgs]("book_meeting", "Books a room.") { (args, ctx) =>
  IO.pure(ctx.requestState match
    case None => ctx.ask("details")("details" -> Elicit.form[Details]("What is the meeting?"))
    case Some(_) =>
      ctx.accepted[Details]("details") match
        case Some(details) => Interaction.done(s"Booked ${args.room}.")
        case None          => Interaction.done("Not booked."))
}
```

The form's `requestedSchema` is derived from `Details`, and `ctx.accepted` decodes
the user's answer back into it with every refinement checked. `Titled["..."]`,
`Default[v]` and `Format["date"]` add `title`, `default` and `format` keywords and
constrain nothing.

`requestState` travels through the client, which could edit it to skip a step.
The `RequestStateCodec` in scope seals it with HMAC-SHA256 over the tool name, the
arguments and the state, and a retry carrying an altered or replayed state never
reaches the handler. Not sealing has to be spelled out:
`RequestStateCodec.unprotected`. A client that did not declare `elicitation` gets
an `isError` result instead of a question it cannot answer.

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

**The schema is derived from the constraints.** A tool's input schema is a
compile-time reading of its argument type — implemented as a macro rather than
`given`s, because Scala cannot decompose an intersection type into two unknowns
during implicit search, and `Interval.Closed[1, 10]` *is* an intersection under
its alias. The macro walks the `TypeRepr`, so arbitrary compositions work and
nothing survives into the runtime.

| Iron constraint | JSON Schema |
|---|---|
| `Not[Empty]` | `minLength: 1` |
| `Interval.Closed[1, 10]` | `minimum: 1, maximum: 10` |
| `Greater[0]` / `GreaterEqual[0]` | `exclusiveMinimum` / `minimum` |
| `Match["^[A-Z]{3}$"]` | `pattern` |
| `MinLength[2]` / `MaxLength[8]` | `minLength` / `maxLength` |
| `C DescribedAs "text"` | `description` (outermost only — see below) |
| `Option[A]` | absent from `required` |
| anything else | no keyword — still enforced by the decoder |

Descriptions live in the type because Iron already has a place for them.
Only the **outermost** `DescribedAs` is read: Iron's own aliases are
`DescribedAs` underneath (`GreaterEqual` carries "Should be greater than or
equal to 1"), so reading every level would smear generated prose over every
field. A bare `Interval.Closed[1, 10]` therefore describes itself as
"Should be included in [1, 10]" — true, and useful to a model.

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
def call(params: CallToolParams): F[CallToolResult | InputRequiredResult]
def get(params: GetPromptParams): F[GetPromptResult | InputRequiredResult]
def read(params: ReadResourceParams): F[ReadResourceResult | InputRequiredResult]
```

**Tool failures are results, not protocol errors.** MCP reserves JSON-RPC errors
for protocol failures; a tool that fails at its job returns `isError: true` so
the model can read and correct it. An Iron violation lands there too:

```json
{"id":4,"result":{"content":[{"type":"text","text":"Should be included in [1, 10]"}],"isError":true}}
```

**No `IO` in the server.** `McpServer[F]`, the providers, `McpTool[F]` and
`ToolSet[F]` are polymorphic in the effect, each asking only for what it uses:

| Piece | Needs |
|---|---|
| `McpServer[F]` (dispatch) | `Applicative` |
| `ToolSet[F]` | `Applicative` |
| `McpTool[A](...) { handler }` | `ApplicativeThrow`: a handler that fails becomes an `isError` result naming the tool |
| `Stdio.serve`, `StdioClient.spawn` | `Async` (blocking stdin/stdout, a child process) |
| `Stdio.pipe`, `McpClient` | `MonadThrow` (+ `Ref.Make` for the client's request ids) |
| `ClientTransport.lines` | `Concurrent` (matching replies to requests) |

`McpTool[Greet](...)` infers `F` from the handler, so `IO` callers write the same
code as before, and the tests run whole servers in plain `Either[Throwable, *]`.

**Tools as programs.** The examples describe the API calls a tool makes as data,
in Bjarnason's "reasonably priced monads" style: an algebra of requests
(`NwsOp` for weather.gov, `ApodOp` for NASA), smart constructors polymorphic in
the coproduct (`Nws[G]`, `Apod[G]`), and an http4s interpreter needing only
`Concurrent`. A tool handler is a `Free` program folded through an interpreter:

```scala
def forecast[G[_]](args: GetForecast)(using N: Nws[G]): Free[G, ForecastResult] =
  for
    pt    <- N.point(args.latitude, args.longitude)
    fcast <- N.forecast(pt.forecast)
  yield ForecastResult("NWS forecast", periodsOf(fcast))

McpTool[GetForecast](name = "get_forecast", description = "...")(forecast[NwsOp](_).foldMap(nws))
```

The same program runs against weather.gov or canned answers, and composes with
other algebras (an LLM's, say) through an `EitherK` coproduct. Core does not
depend on cats-free; only the examples do.

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
| `server/Providers.scala` | one trait per capability, polymorphic in `F` |
| `server/McpServer.scala` | dispatch, version checks, capability derivation |
| `server/McpTool.scala`, `server/Interaction.scala` | typed tools; `McpTool.interactive`, `Elicit`, `RequestStateCodec` |
| `transport/{Wire,Stdio}.scala` | newline-delimited JSON-RPC on stdin/stdout |
| `client/McpClient.scala` | the client: typed calls, `callToolGathering`, `InputHandlers` |
| `client/{ClientTransport,StdioClient}.scala` | in-process, stdio framing, child processes |
| `modules/http` (`iron-mcp-http`) | Streamable HTTP on http4s ember, server and client, JVM-only |

### Streamable HTTP

```scala
libraryDependencies += "io.github.danbills" %% "iron-mcp-http" % "<version>"

McpHttp.serve(McpHttp.routes(server), port = 5000).useForever
```

Stateless, as the revision intends: one POST per message, a JSON reply per
request, `202` for a notification, `405` for GET and DELETE. `MCP-Protocol-Version`,
`Mcp-Method` and `Mcp-Name` are required and checked against the body (`400`,
`-32020` on a mismatch); an unsupported version is `400` with `-32022`, an
unknown method `404` with `-32601`. `Origin` and `Host` must be loopback by
default (`403` otherwise, against DNS rebinding), and bodies over 4 MiB are
refused unread. `McpHttp.routesFor(config)(request => ...)` builds the server per
request, which is how an access token's claims reach the tools.

### A client

```scala
StdioClient.spawn[IO]("./my-server").use { transport =>   // or McpHttpClient.ember(uri"http://localhost:5000/mcp")
  for
    client <- McpClient[IO](transport)
    tools  <- client.allTools
    result <- client.callTool("greet", JsonObject("name" -> "Ada".asJson, "times" -> 2.asJson))
  yield result
}
```

Every request carries the 2026-07-28 `_meta` envelope, so there is no handshake.
Calls return the protocol's own result types; a JSON-RPC error is raised as
`McpClientError.Rpc`, and an HTTP rejection as `McpClientError.Http` with its
`WWW-Authenticate` challenges (`McpHttpClient.challenges` parses them).

Calls that may answer `input_required` return `A | InputRequiredResult` for a
caller that runs its own retry loop. `callToolGathering` runs it for you with
the client's `InputHandlers`, which also declare its capabilities:

```scala
McpClient[IO](transport, handlers = InputHandlers.elicitation(params => askTheUser(params)))
  .flatMap(_.callToolGathering("book_meeting", args, maxRounds = 10, deadline = 2.minutes))
```

The deadline is one timeout around the whole exchange, so it also cancels a
prompt still waiting on the user. `ClientTransport.inProcess(server)` runs a
client against a server in the same process, through the wire encoding, which
is how the tests work; `ClientTransport.lines` is the stdio framing over any
pair of streams. The demo binary has a client mode: `ironmcp.demo.Main client
<server command>`.

## Using it

```scala
libraryDependencies += "io.github.danbills" %% "iron-mcp-core" % "<version>"
```

Published for the JVM and Scala Native 0.5 from the same source.

## Running

```bash
sbt "coreJVM/Test/testFull"   # protocol and schema suites
sbt "http/Test/testFull"      # Streamable HTTP server and client, in memory
sbt "weather/Test/testFull" "nasa/Test/testFull"   # the example tools, offline
sbt "demoJVM/run"             # stdio server on the JVM
sbt "demoNative/nativeLink"   # native binary
```

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{
  "io.modelcontextprotocol/protocolVersion":"2026-07-28",
  "io.modelcontextprotocol/clientCapabilities":{}}}}' \
| ./target/out/native0.5/scala-3.9.0/iron-mcp-demo/native/ironmcp.demo.Main
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
- **Scala 3.9.0**, the next LTS (succeeding 3.3). Native works because
  `nscplugin_3.9.0` is published for Scala Native 0.5.12; the compiler must
  match the Scala version exactly.
- **Warnings are errors.** The build runs `-Wunused:all -Wvalue-discard -Werror`
  on every module, test sources and Scala Native included. Code shared between
  the JVM and Native must be clean on both: a `@nowarn` that suppresses
  something on one platform is itself a warning on the other, so mark the
  symbol `@annotation.unused` instead (see `Stdio.serve`).
- **Scala 3.9.0 crashes on clause interleaving with named arguments**
  (`unexpected tree for type application`), which is why `McpTool.apply`
  returns a `Builder` instead of taking a second type-parameter clause.

## Stack

Scala 3.9.0 · Iron 3.3.2 · Circe 0.14.16 · cats-effect 3.7.0 · fs2 3.13.0 ·
Scala Native 0.5.12 · sbt 2.0.9. The JVM-only HTTP module and examples add
http4s 0.23.37 (Ember) and cats-free 2.13.0.

## Status

The protocol layer, dispatcher and stdio transport are complete and tested,
on the JVM and Scala Native, and so is the client (stdio and in-process); Streamable HTTP, server and client, and elicitation (MRTR) on the JVM. Two example servers call real APIs:
`modules/weather` (weather.gov forecast, conditions and alerts; no key) and
`modules/nasa` (Astronomy Picture of the Day; `NASA_API_KEY`). Their tools are
`Free` programs over request algebras, tested offline.

Not yet built: resource, prompt and completion providers beyond the traits,
SSE responses from the server (the client reads them), a legacy-era (`initialize`) client, the Tasks extension,
and capability providers such as email, web search or computer use.

## Releasing

Releases are cut locally. sbt 2 uploads to the Sonatype Central Portal itself,
so there is no CI plugin and no workflow — `sbt-dynver` takes the version from
the git tag (`build.sbt` deliberately sets no `version`) and `sbt-pgp` signs.

```bash
git tag -a v0.1.0 -m "v0.1.0"

export SONATYPE_USERNAME=...   # Central Portal user token, not your login
export SONATYPE_PASSWORD=...
export PGP_PASSPHRASE=...

sbt +publishSigned   # sign, into a local bundle
sbt sonaUpload       # upload; release by hand in the portal
# or sonaRelease     # upload and release in one step
```

The version is only clean (`0.1.0`) when the tree is clean at the tag —
sbt-dynver appends a commit and timestamp otherwise, which is a useful guard
against releasing uncommitted work.

## License

MIT
