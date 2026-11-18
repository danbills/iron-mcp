package ironmcp

import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import munit.FunSuite

class ProtocolCodecsSuite extends FunSuite:

  private def roundTrip[A: Encoder: Decoder](a: A): Unit =
    assertEquals(decode[A](a.asJson.noSpaces), Right(a))

  test("Common enums and models round-trip and reject invalid values"):
    roundTrip(Role.user)
    roundTrip(Role.assistant)
    assert(decode[Role]("\"system\"").isLeft)

    for lvl <- LoggingLevel.values do roundTrip(lvl)
    assert(decode[LoggingLevel]("\"bogus\"").isLeft)

    roundTrip(ResultType.Complete: ResultType)
    roundTrip(ResultType.InputRequired: ResultType)
    roundTrip(ResultType.Extension("ext"): ResultType)

    roundTrip(CacheScope.`public`)
    roundTrip(CacheScope.`private`)
    assert(decode[CacheScope]("\"shared\"").isLeft)
    assertEquals(CachePolicy.privateShortLived.cacheScope, CacheScope.`private`)

    roundTrip(IconTheme.light)
    roundTrip(IconTheme.dark)
    assert(decode[IconTheme]("\"neon\"").isLeft)

    roundTrip(Icon("https://example.com/icon.png", Some("image/png"), Some(List("16x16")), Some(IconTheme.dark)))
    roundTrip(Implementation("srv", "1.0", Some("Server"), Some("desc"), Some("https://example.com"), Some(List(Icon("https://example.com/i.png")))))

  test("Completion, Subscriptions, Prompts, Sampling, Notifications, Capabilities, Discover round-trip"):
    val meta = RequestMeta(LatestProtocolVersion, ClientCapabilities.none)
    roundTrip(CompletionReference.ForPrompt("p", Some("Prompt")): CompletionReference)
    roundTrip(CompletionReference.ForResourceTemplate("file:///a/{b}"): CompletionReference)
    assert(decode[CompletionReference]("""{"type":"ref/other"}""").isLeft)
    roundTrip(CompleteParams(CompletionReference.ForPrompt("p"), CompletionArgument("arg", "v"), meta, Some(CompletionContext(Some(Map("k" -> "v"))))))
    roundTrip(CompleteResult(Completion(List("a", "b"), Some(2L), Some(false))))

    roundTrip(SubscriptionFilter(Some(true), Some(false), Some(true), Some(List("file:///a"))))
    roundTrip(SubscriptionsListenParams(SubscriptionFilter(), meta))
    roundTrip(SubscriptionsListenResult(RequestId.Num(1L), Some(Implementation("s", "1"))))
    assert(decode[SubscriptionsListenResult]("""{"resultType":"complete","_meta":{}}""").isLeft)

    roundTrip(PromptArgument("a", Some("A"), Some("desc"), Some(true)))
    roundTrip(Prompt("p", Some("Title"), Some("desc"), Some(List(PromptArgument("a")))))
    roundTrip(PromptMessage(Role.user, ContentBlock.Text("hi")))

    roundTrip(ModelHint(Some("claude")))
    roundTrip(ModelPreferences(Some(List(ModelHint(Some("m")))), Some(0.5), Some(0.5), Some(0.5)))

    roundTrip(ProgressParams(ProgressToken.Text("t"), 1.0, Some(2.0), Some("halfway")))
    roundTrip(CancelledParams(RequestId.Num(3L), Some("timeout")))
    roundTrip(LoggingMessageParams(LoggingLevel.info, Json.fromString("ok"), Some("app")))

    roundTrip(ServerCapabilities(Some(ToolsCapability(Some(true))), Some(ResourcesCapability(Some(true), Some(true))), Some(PromptsCapability(Some(true))), Some(JsonObject.empty), None, None, Some(Map("ext" -> JsonObject.empty))))
    val cc = ClientCapabilities(Some(JsonObject.empty), Some(SamplingCapability()), Some(ElicitationCapability()), None, Some(Map("ext" -> JsonObject.empty)))
    roundTrip(cc)
    assert(cc.supportsSampling && cc.supportsElicitation && cc.supportsRoots)
    roundTrip(DiscoverResult(List(LatestProtocolVersion), ServerCapabilities(), 60000L, CacheScope.`public`, Some("hi")))

  test("Content blocks, JsonRpc, Meta, ToolOutput, Elicitation, Schema round-trip"):
    roundTrip(Annotations(Some(List(Role.user)), Some(0.8), Some("2026-01-01T00:00:00Z")))
    val ann = Some(Annotations(Some(List(Role.assistant))))
    roundTrip(ContentBlock.Text("hi", ann): ContentBlock)
    roundTrip(ContentBlock.Image("abc", "image/png", ann): ContentBlock)
    roundTrip(ContentBlock.Audio("abc", "audio/mp3", ann): ContentBlock)
    assert(decode[ContentBlock]("""{"type":"unknown"}""").isLeft)

    roundTrip(ResourceContents.Text("file:///a", "hi", Some("text/plain")): ResourceContents)
    roundTrip(ResourceContents.Blob("file:///b", "AQID", Some("application/octet-stream")): ResourceContents)
    assert(decode[ResourceContents]("""{"uri":"file:///c"}""").isLeft)

    val req: JsonRpcMessage = JsonRpcMessage.Request(RequestId.Text("r1"), "ping", Some(Json.obj()))
    val not: JsonRpcMessage = JsonRpcMessage.Notification("notifications/ping", None)
    val ok: JsonRpcMessage  = JsonRpcMessage.Success(RequestId.Num(1L), Json.obj())
    val err: JsonRpcMessage = JsonRpcMessage.Failure(None, JsonRpcError(ErrorCode.InternalError, "boom", Some(Json.obj("k" -> "v".asJson))))
    for m <- List(req, not, ok, err) do roundTrip(m)
    assert(decode[JsonRpcMessage]("""{"jsonrpc":"1.0","id":1,"method":"ping"}""").isLeft)
    assert(decode[RequestId]("true").isLeft)
    assert(decode[ProgressToken]("true").isLeft)
    assertEquals(ErrorCode.fromInt(-32099).code, -32099)
    assertEquals(ErrorCode.fromInt(42).code, 42)

    val keyX: MetaKey = "x"
    val keyY: MetaKey = "y"
    roundTrip(RequestMeta(LatestProtocolVersion, ClientCapabilities(), Some(Implementation("c", "1")), None, Some(ProgressToken.Num(5L)), Map(keyX -> Json.True)))
    roundTrip(ResultMeta(Some(Implementation("s", "1")), Some(RequestId.Num(6L)), Map(keyY -> Json.False)))
    assertEquals(ToolAnnotations().readOnlyHint, None)
    assertEquals(ToolOutput.raw.schema, None)
    assertEquals(ToolOutput.text.result("ok").content.size, 1)
    assertEquals(CallToolResult.failed("bad").isError, Some(true))
    roundTrip(ElicitResult(ElicitAction.Accept, Some(JsonObject("a" -> "b".asJson))))
    roundTrip(ElicitResult(ElicitAction.Decline))
    roundTrip(ElicitResult(ElicitAction.Cancel))
    assert(decode[ElicitAction]("\"skip\"").isLeft)
    assert(summon[JsonSchemaOf[Option[Int]]].schema.asJson.noSpaces.contains("integer"))
