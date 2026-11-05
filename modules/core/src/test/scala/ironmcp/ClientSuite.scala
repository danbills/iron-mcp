package ironmcp

import cats.effect.IO
import io.circe.{Decoder, Encoder, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import munit.CatsEffectSuite

/** Every client method against an in-process server. */
class ClientSuite extends CatsEffectSuite:

  final case class EchoArgs(message: String :| Not[Empty]) derives Decoder, JsonSchemaOf
  final case class Echoed(echo: String) derives Encoder.AsObject, JsonSchemaOf

  private val echo = McpTool[EchoArgs]("echo", "Echoes.")(args => IO.pure(Echoed(args.message)))

  /** Lists its tools two to a page, to make the client follow `nextCursor`. */
  private val paged = new ToolProvider[IO]:
    private val names = List("a", "b", "c", "d", "e")
    def list(params: ListToolsParams): IO[ListToolsResult] =
      val from = params.cursor.fold(0)(c => (c: String).toInt)
      val next: Option[Cursor] = Option.when(from + 2 < names.size)((from + 2).toString.assume)
      IO.pure(ListToolsResult(names.slice(from, from + 2).map(n => Tool(name = n.assume, inputSchema = ObjectSchema.empty)), 0L, CacheScope.`private`, next))
    def call(params: CallToolParams): IO[CallToolResult | InputRequiredResult] = IO.pure(CallToolResult.text(params.name))

  private val resources = new ResourceProvider[IO]:
    def list(params: ListResourcesParams): IO[ListResourcesResult] =
      IO.pure(ListResourcesResult(List(Resource(uri = "file:///a.md", name = "a")), 0L, CacheScope.`private`))
    override def templates(params: ListResourcesParams): IO[ListResourceTemplatesResult] =
      IO.pure(ListResourceTemplatesResult(List(ResourceTemplate(uriTemplate = "file:///{name}", name = "files")), 0L, CacheScope.`private`))
    def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult] =
      if (params.uri: String) == "file:///a.md" then
        IO.pure(ReadResourceResult(List(ResourceContents.Text(params.uri, "# A")), 0L, CacheScope.`private`))
      else IO.raiseError(McpException.resourceNotFound(params.uri))

  private val prompts = new PromptProvider[IO]:
    def list(params: ListPromptsParams): IO[ListPromptsResult] =
      IO.pure(ListPromptsResult(List(Prompt(name = "summarize")), 0L, CacheScope.`private`))
    def get(params: GetPromptParams): IO[GetPromptResult | InputRequiredResult] =
      val topic = params.arguments.flatMap(_.get("topic")).getOrElse("nothing")
      IO.pure(GetPromptResult(List(PromptMessage(Role.user, ContentBlock.Text(s"Summarize $topic.")))))

  private val completions = new CompletionProvider[IO]:
    def complete(params: CompleteParams): IO[CompleteResult] =
      IO.pure(CompleteResult(Completion(List("alpha", "alps").filter(_.startsWith(params.argument.value)), Some(2L), Some(false))))

  private val server = McpServer[IO](
    info = Implementation(name = "client-suite", version = "0.1.0"),
    instructions = Some("For the client tests."),
    tools = Some(ToolSet.of(echo)),
    resources = Some(resources),
    prompts = Some(prompts),
    completions = Some(completions)
  )

  private def client(of: McpServer[IO] = server) = McpClient[IO](ClientTransport.inProcess(of))

  test("discover returns the revision, the capabilities and the instructions"):
    client().flatMap(_.discover).map { result =>
      assertEquals(result.supportedVersions.map(v => v: String), List("2026-07-28"))
      assert(result.capabilities.tools.isDefined && result.capabilities.completions.isDefined)
      assertEquals(result.instructions.map(i => i: String), Some("For the client tests."))
    }

  test("tools/list and tools/call, with structured output"):
    for
      c      <- client()
      tools  <- c.allTools
      result <- c.callTool("echo", JsonObject("message" -> "hi".asJson))
    yield
      assertEquals(tools.map(t => t.name: String), List("echo"))
      result match
        case r: CallToolResult => assertEquals(r.structuredContent, Some(io.circe.Json.obj("echo" -> "hi".asJson)))
        case other             => fail(s"unexpected $other")

  test("allTools follows nextCursor to the end"):
    McpClient[IO](ClientTransport.inProcess(McpServer[IO](info = Implementation(name = "paged", version = "0"), tools = Some(paged))))
      .flatMap(_.allTools)
      .map(tools => assertEquals(tools.map(t => t.name: String), List("a", "b", "c", "d", "e")))

  test("a refinement violation is a tool error, not a client error"):
    client().flatMap(_.callTool("echo", JsonObject("message" -> "".asJson))).map {
      case r: CallToolResult => assertEquals(r.isError, Some(true))
      case other             => fail(s"unexpected $other")
    }

  test("resources: list, templates, read, and -32002 raised as an Rpc error"):
    for
      c         <- client()
      listed    <- c.allResources
      templates <- c.allResourceTemplates
      read      <- c.readResource("file:///a.md")
      missing   <- c.readResource("file:///nope").attempt
    yield
      assertEquals(listed.map(r => r.name: String), List("a"))
      assertEquals(templates.map(t => t.uriTemplate: String), List("file:///{name}"))
      read match
        case r: ReadResourceResult => assertEquals(r.contents, List(ResourceContents.Text("file:///a.md", "# A")))
        case other                 => fail(s"unexpected $other")
      missing match
        case Left(McpClientError.Rpc(error)) => assertEquals(error.code, ErrorCode.ResourceNotFound)
        case other                           => fail(s"unexpected $other")

  test("prompts: list and get with arguments"):
    for
      c      <- client()
      listed <- c.listPrompts()
      got    <- c.getPrompt("summarize", Map("topic" -> "the release notes"))
    yield
      assertEquals(listed.prompts.map(p => p.name: String), List("summarize"))
      got match
        case r: GetPromptResult => assertEquals(r.messages.map(_.content), List(ContentBlock.Text("Summarize the release notes.")))
        case other              => fail(s"unexpected $other")

  test("completion/complete"):
    client().flatMap(_.complete(CompletionReference.ForResourceTemplate("file:///{name}"), "name", "alp")).map { result =>
      assertEquals(result.completion.values, List("alpha", "alps"))
    }

  test("a method the server does not serve is -32601"):
    client(McpServer[IO](info = Implementation(name = "bare", version = "0"))).flatMap(_.listPrompts().attempt).map {
      case Left(McpClientError.Rpc(error)) => assertEquals(error.code, ErrorCode.MethodNotFound)
      case other                           => fail(s"unexpected $other")
    }

  test("every request carries the 2026-07-28 envelope with the client's info"):
    val seen = new ToolProvider[IO]:
      def list(params: ListToolsParams): IO[ListToolsResult] =
        IO.pure(ListToolsResult(Nil, 0L, CacheScope.`private`, _meta = Some(ResultMeta(extra = Map(
          ("seen": MetaKey) -> params._meta.clientInfo.fold(io.circe.Json.Null)(_.asJson)
        )))))
      def call(params: CallToolParams): IO[CallToolResult | InputRequiredResult] = IO.pure(CallToolResult.text(""))
    McpClient[IO](ClientTransport.inProcess(McpServer[IO](info = Implementation(name = "s", version = "0"), tools = Some(seen))),
      info = Implementation(name = "suite-client", version = "9.9.9"))
      .flatMap(_.listTools())
      .map(r => assertEquals(r._meta.flatMap(_.extra.get("seen")).flatMap(_.hcursor.get[String]("name").toOption), Some("suite-client")))

  test("a tool's properties keep their declared order, past four of them"):
    final case class Six(f: Int, e: Int, d: Int, c: Int, b: Int, a: Int) derives Decoder, JsonSchemaOf
    val six = McpTool[Six]("six", "Six fields.")(_ => IO.pure("ok"))
    McpClient[IO](ClientTransport.inProcess(McpServer[IO](info = Implementation(name = "order", version = "0"), tools = Some(ToolSet.of(six)))))
      .flatMap(_.allTools)
      .map(tools => assertEquals(tools.head.inputSchema.properties.keys.toList, List("f", "e", "d", "c", "b", "a")))
