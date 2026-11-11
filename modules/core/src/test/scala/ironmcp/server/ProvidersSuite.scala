package ironmcp
package server

import cats.effect.IO
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.CatsEffectSuite

/** The providers' defaults, and ResourceProvider.all combining several. */
class ProvidersSuite extends CatsEffectSuite:

  private val meta = RequestMeta(LatestProtocolVersion, ClientCapabilities.none)

  /** Serves one URI; any other is -32002, or `otherError` when given. */
  private def serving(uri: String, ttl: Long, scope: CacheScope, otherError: Option[Throwable] = None): ResourceProvider[IO] =
    new ResourceProvider[IO]:
      def list(params: ListResourcesParams) =
        IO.pure(ListResourcesResult(List(Resource(uri = uri.refineUnsafe, name = uri.refineUnsafe)), ttl.refineUnsafe, scope))
      override def templates(params: ListResourcesParams) =
        IO.pure(ListResourceTemplatesResult(List(ResourceTemplate(uriTemplate = s"$uri/{x}".refineUnsafe, name = uri.refineUnsafe)), ttl.refineUnsafe, scope))
      def read(params: ReadResourceParams) =
        if (params.uri: String) == uri then IO.pure(ReadResourceResult(List(ResourceContents.Text(params.uri, uri)), ttl.refineUnsafe, scope))
        else IO.raiseError(otherError.getOrElse(McpException.resourceNotFound(params.uri)))

  private def read(provider: ResourceProvider[IO], uri: String): IO[String] =
    provider.read(ReadResourceParams(uri.refineUnsafe, meta)).map {
      case r: ReadResourceResult => r.contents.collect { case ResourceContents.Text(_, text, _, _) => text }.mkString
      case other                 => fail(s"not read: $other")
    }

  test("a provider without templates lists none, publicly uncached"):
    val bare = new ResourceProvider[IO]:
      def list(params: ListResourcesParams) = IO.pure(ListResourcesResult(Nil, 0L, CacheScope.`private`))
      def read(params: ReadResourceParams)  = IO.raiseError(McpException.resourceNotFound(params.uri))
    bare.templates(ListResourcesParams(meta)).map(r => assertEquals(r.resourceTemplates, Nil))

  test("all: lists and templates together, cached as briefly and privately as the strictest part"):
    val all = ResourceProvider.all(serving("a://1", 5000L, CacheScope.`public`), serving("b://2", 100L, CacheScope.`private`))
    for
      listed    <- all.list(ListResourcesParams(meta))
      templates <- all.templates(ListResourcesParams(meta))
    yield
      assertEquals(listed.resources.map(_.uri: String), List("a://1", "b://2"))
      assertEquals((listed.ttlMs: Long, listed.cacheScope), (100L, CacheScope.`private`))
      assertEquals(templates.resourceTemplates.map(_.uriTemplate: String), List("a://1/{x}", "b://2/{x}"))
      assertEquals((templates.ttlMs: Long, templates.cacheScope), (100L, CacheScope.`private`))

  test("all: public only when every part is public"):
    ResourceProvider.all(serving("a://1", 10L, CacheScope.`public`), serving("b://2", 20L, CacheScope.`public`))
      .list(ListResourcesParams(meta)).map(r => assertEquals(r.cacheScope, CacheScope.`public`))

  test("all: a read goes to the first provider that knows the URI"):
    val all = ResourceProvider.all(serving("a://1", 0L, CacheScope.`private`), serving("b://2", 0L, CacheScope.`private`))
    for
      first  <- read(all, "a://1")
      second <- read(all, "b://2")
      none   <- read(all, "c://3").attempt
    yield
      assertEquals((first, second), ("a://1", "b://2"))
      none match
        case Left(McpException(error)) => assertEquals(error.code, ErrorCode.ResourceNotFound)
        case other                     => fail(s"not -32002: $other")

  test("all: an error other than -32002 stops the search"):
    val all = ResourceProvider.all(serving("a://1", 0L, CacheScope.`private`, Some(RuntimeException("disk on fire"))), serving("b://2", 0L, CacheScope.`private`))
    read(all, "b://2").attempt.map(r => assertEquals(r.left.map(_.getMessage), Left("disk on fire")))

  test("a ToolProvider that doesn't report progress answers callEmitting with call"):
    val plain = new ToolProvider[IO]:
      def list(params: ListToolsParams) = IO.pure(ListToolsResult(Nil, 0L, CacheScope.`private`))
      def call(params: CallToolParams)  = IO.pure(CallToolResult.text(params.name))
    plain.callEmitting(CallToolParams("hello", meta), Emit.none[IO]).map {
      case r: CallToolResult => assertEquals(r.content, List(ContentBlock.Text("hello")))
      case other             => fail(s"$other")
    }
