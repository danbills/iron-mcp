package ironmcp
package apps

import cats.effect.IO
import io.circe.{Decoder, Json, JsonObject, parser}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.transport.Wire
import munit.CatsEffectSuite

class AppsSuite extends CatsEffectSuite:

  final case class NoArgs(dummy: Option[String]) derives Decoder, JsonSchemaOf

  private val view = UiResource("ui://demo/view.html", "Demo View", "<html><body>hi</body></html>", meta = UiResourceMeta(csp = Some(UiCsp(connectDomains = Some(List("https://api.example.com")))), permissions = Some(UiPermissions.microphone ++ UiPermissions.clipboardWrite), prefersBorder = Some(true)))

  private val server = McpServer[IO](
    Implementation("apps", "0"),
    tools = Some(
      ToolSet.of(
        McpTool[NoArgs]("show", "Shows the view.")(_ => IO.pure("shown")).withUi("ui://demo/view.html"),
        McpTool[NoArgs]("refresh", "View-only refresh.")(_ => IO.pure("refreshed")).withUi("ui://demo/view.html", Visibility.App),
        McpTool[NoArgs]("poll", "Polled by the View.")(_ => IO.pure("stats")).withVisibility(Visibility.App)
      )
    ),
    resources = Some(UiResources.of[IO](view))
  )

  private def client = McpClient[IO](ClientTransport.inProcess(server))

  test("tools/list carries _meta.ui and the legacy ui/resourceUri mirror"):
    client.flatMap(_.listTools()).map { result =>
      val json = parser.parse(Wire.json(result.asJson)).toOption.get
      val tools = json.hcursor.downField("tools").as[List[Json]].toOption.get
      val show  = tools.head.hcursor.downField("_meta")
      assertEquals(show.downField("ui").get[String]("resourceUri"), Right("ui://demo/view.html"))
      assert(show.downField("ui").downField("visibility").failed, "no visibility means the host default")
      assertEquals(show.get[String]("ui/resourceUri"), Right("ui://demo/view.html"))
      assertEquals(tools(1).hcursor.downField("_meta").downField("ui").get[List[String]]("visibility"), Right(List("app")))
      val poll = tools(2).hcursor.downField("_meta")
      assertEquals(poll.downField("ui").focus, Some(Json.obj("visibility" -> Json.arr(Json.fromString("app")))))
      assert(poll.downField("ui/resourceUri").failed, "no View, so no legacy URI either")
    }

  test("the View is listed and read as text/html;profile=mcp-app, with its _meta.ui"):
    for
      c        <- client
      listed   <- c.listResources()
      read     <- c.readResource("ui://demo/view.html")
    yield
      assertEquals(listed.resources.map(_.mimeType.map(m => m: String)), List(Some("text/html;profile=mcp-app")))
      read match
        case r: ReadResourceResult =>
          r.contents match
            case List(ResourceContents.Text(_, html, mime, meta)) =>
              assertEquals(html, "<html><body>hi</body></html>")
              assertEquals(mime.map(m => m: String), Some("text/html;profile=mcp-app"))
              val ui = meta.flatMap(_.get(McpApps.UiKey)).get
              assertEquals(ui.hcursor.downField("csp").get[List[String]]("connectDomains"), Right(List("https://api.example.com")))
              assertEquals(ui.hcursor.get[Boolean]("prefersBorder"), Right(true))
              assertEquals(ui.hcursor.downField("permissions").focus, Some(Json.obj("microphone" -> Json.obj(), "clipboardWrite" -> Json.obj())))
            case other => fail(s"unexpected $other")
        case other => fail(s"unexpected $other")

  test("an unknown ui:// URI is -32002"):
    client.flatMap(_.readResource("ui://demo/missing.html")).attempt.map {
      case Left(McpClientError.Rpc(error)) => assertEquals(error.code.code, -32002)
      case other                           => fail(s"unexpected $other")
    }

  test("a View URI must use the ui:// scheme"):
    assert(compileErrors("""val bad: UiUri = "https://example.com/view.html"""").nonEmpty)

  test("clientSupports reads the extension's mimeTypes"):
    val apps = ClientCapabilities(extensions = Some(Map(McpApps.ExtensionId -> JsonObject("mimeTypes" -> Json.arr(Json.fromString("text/html;profile=mcp-app"))))))
    assert(McpApps.clientSupports(apps))
    assert(!McpApps.clientSupports(ClientCapabilities.none))
    assert(!McpApps.clientSupports(ClientCapabilities(extensions = Some(Map(McpApps.ExtensionId -> JsonObject.empty)))))

  test("media types with parameters are valid MimeTypes; malformed ones are not"):
    assert("text/html;profile=mcp-app".refineOption[MimeTypeC].isDefined)
    assert("text/plain; charset=\"utf-8\"".refineOption[MimeTypeC].isDefined)
    assert("text/html;".refineOption[MimeTypeC].isEmpty)
    assert("html".refineOption[MimeTypeC].isEmpty)

  test("UiHtml inlines a script at the slot, else before </body>, and escapes what would end it"):
    val script = """var s = "</script><!-- x"; var t = "</SCRIPT>"; var cost = "$1";"""
    val slotted = UiHtml.inlineScript(s"<html><body><div id=app></div>${UiHtml.ScriptSlot}<p>after</p></body></html>", script)
    assert(slotted.contains("<div id=app></div><script>\n"), slotted)
    assert(slotted.contains("</script><p>after</p>"), slotted)
    assert(slotted.contains("""var s = "<\/script><\!-- x"; var t = "<\/SCRIPT>"; var cost = "$1";"""), slotted)
    assertEquals(slotted.split("</script>", -1).length, 2, "exactly one closing tag")
    val bodied = UiHtml.inlineScript("<html><BODY><p>x</p></BODY></html>", "go()")
    assertEquals(bodied, "<html><BODY><p>x</p><script>\ngo()\n</script>\n</BODY></html>")

  test("UiHtml inlines styles at the slot, else before </head>, and escapes what would end them"):
    val css = """a::after { content: "</style>$1"; } /* </STYLE> */"""
    val slotted = UiHtml.inlineStyle(s"<html><head><title>t</title>${UiHtml.StyleSlot}</head><body></body></html>", css)
    assert(slotted.contains("""<title>t</title><style>
a::after { content: "<\/style>$1"; } /* <\/STYLE> */
</style></head>"""), slotted)
    assertEquals(slotted.split("</style>", -1).length, 2, "exactly one closing tag")
    assertEquals(UiHtml.inlineStyle("<html><HEAD></HEAD></html>", "p{}"), "<html><HEAD><style>\np{}\n</style>\n</HEAD></html>")
    assertEquals(UiHtml.inlineStyle("<p>x</p>", "p{}"), "<style>\np{}\n</style>\n<p>x</p>")

  test("ResourceProvider.all lists every provider's resources and reads from the one that knows the URI"):
    val videos = new ResourceProvider[IO]:
      def list(params: ListResourcesParams): IO[ListResourcesResult] =
        IO.pure(ListResourcesResult(List(Resource(uri = "videos://bunny", name = "video-bunny")), 1000L, CacheScope.`private`))
      def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult] =
        if params.uri == "videos://bunny" then IO.pure(ReadResourceResult(List(ResourceContents.Text(params.uri, "mp4")), 1000L, CacheScope.`private`))
        else IO.raiseError(McpException.resourceNotFound(params.uri))
    val both   = McpServer[IO](Implementation("apps", "0"), resources = Some(ResourceProvider.all(UiResources.of[IO](view), videos)))
    for
      c       <- McpClient[IO](ClientTransport.inProcess(both))
      listed  <- c.listResources()
      ui      <- c.readResource("ui://demo/view.html")
      video   <- c.readResource("videos://bunny")
      missing <- c.readResource("videos://nope").attempt
    yield
      assertEquals(listed.resources.map(r => r.uri: String), List("ui://demo/view.html", "videos://bunny"))
      assertEquals(listed.ttlMs: Long, 1000L)
      assertEquals(listed.cacheScope, CacheScope.`private`)
      assert(ui.isInstanceOf[ReadResourceResult] && video.isInstanceOf[ReadResourceResult])
      missing match
        case Left(McpClientError.Rpc(error)) => assertEquals(error.code.code, -32002)
        case other                           => fail(s"unexpected $other")
