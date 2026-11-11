package ironmcp
package server

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.CatsEffectSuite

/** McpServer's dispatch, branch by branch: the parts the protocol-level suites don't reach. */
class McpServerSuite extends CatsEffectSuite:

  private val meta = RequestMeta(LatestProtocolVersion, ClientCapabilities.none)

  private def request(method: String, params: Option[Json]): JsonRpcMessage =
    JsonRpcMessage.Request(RequestId.Num(1), method.refineUnsafe, params)

  private def withMeta(fields: (String, Json)*): Option[Json] = Some(Json.obj(("_meta" -> meta.asJson) +: fields*))

  private val subscriptions = new SubscriptionProvider[IO]:
    def listen(params: SubscriptionsListenParams): IO[SubscriptionsListenResult] =
      IO.pure(SubscriptionsListenResult(RequestId.Text("sub-1")))

  private val server = McpServer[IO](
    info = Implementation(name = "server-suite", version = "0.1.0"),
    subscriptions = Some(subscriptions),
    customHandlers = Map("x/echo" -> (params => IO.pure(Right(params.getOrElse(Json.Null)))))
  )

  private def success(reply: Option[JsonRpcMessage]): Json = reply match
    case Some(JsonRpcMessage.Success(_, result)) => result
    case other                                   => fail(s"not a success: $other")

  private def failure(reply: Option[JsonRpcMessage]): JsonRpcError = reply match
    case Some(JsonRpcMessage.Failure(_, error)) => error
    case other                                  => fail(s"not a failure: $other")

  test("handle answers a request without a sink"):
    server.handle(request("x/echo", Some(Json.obj("a" -> 1.asJson)))).map(r => assertEquals(success(r), Json.obj("a" -> 1.asJson)))

  test("handleRecovering answers a request like handle"):
    server.handleRecovering(request("x/echo", None)).map(r => assertEquals(success(r), Json.Null))

  test("a response sent to a server gets no reply: it never asked anything"):
    for
      s <- server.handle(JsonRpcMessage.Success(RequestId.Num(9), Json.obj()))
      f <- server.handle(JsonRpcMessage.Failure(Some(RequestId.Num(9)), JsonRpcError(ErrorCode.InternalError, "x")))
    yield assertEquals((s, f), (None, None))

  test("subscriptions/listen answers with the provider's subscription id"):
    server.handle(request("subscriptions/listen", withMeta("notifications" -> Json.obj("toolsListChanged" -> true.asJson))))
      .map(r => assertEquals(success(r).hcursor.downField("_meta").get[String]("io.modelcontextprotocol/subscriptionId"), Right("sub-1")))

  test("subscriptions/listen with a foreign protocol version is -32022"):
    val foreign = Json.obj("_meta" -> meta.copy(protocolVersion = "2025-11-25").asJson, "notifications" -> Json.obj())
    server.handle(request("subscriptions/listen", Some(foreign))).map(r => assertEquals(failure(r).code, ErrorCode.UnsupportedProtocolVersion))

  test("a custom handler answers its own method, with the params as sent"):
    server.handle(request("x/echo", Some(Json.arr(1.asJson)))).map(r => assertEquals(success(r), Json.arr(1.asJson)))

  test("a request with no params at all is decoded as an empty object, and refused for its missing _meta"):
    server.handle(request("subscriptions/listen", None)).map(r => assertEquals(failure(r).code, ErrorCode.InvalidParams))

  test("capabilities: subscriptions turn on resources.subscribe only alongside resources"):
    assertEquals(server.capabilities.resources, None)
    assertEquals(server.capabilities.tools, None)
    val withResources = McpServer[IO](
      info = Implementation(name = "x", version = "1"),
      resources = Some(new ResourceProvider[IO]:
        def list(params: ListResourcesParams) = IO.pure(ListResourcesResult(Nil, 0L, CacheScope.`private`))
        def read(params: ReadResourceParams)  = IO.raiseError(McpException.resourceNotFound(params.uri))
      ),
      subscriptions = Some(subscriptions)
    )
    assertEquals(withResources.capabilities.resources.flatMap(_.subscribe), Some(true))

  test("unknownTool is -32602 naming the tool"):
    val error = McpException.unknownTool("nope").error
    assertEquals((error.code, error.message: String), (ErrorCode.InvalidParams, "Unknown tool: nope"))

  test("handle and handleRecovering have nowhere to send progress: a reporting tool still answers, its progress dropped"):
    final case class NoArgs() derives io.circe.Decoder, ironmcp.schema.JsonSchemaOf
    val counting = McpServer[IO](
      info = Implementation(name = "progress", version = "1"),
      tools = Some(ToolSet.of(McpTool.reporting[IO, NoArgs]("count", "Counts.")((_, emit) => emit.progress(1, Some(2)).as("counted"))))
    )
    val call = request("tools/call", Some(Json.obj(
      "_meta" -> meta.copy(progressToken = Some(ProgressToken.Num(3))).asJson,
      "name" -> "count".asJson,
      "arguments" -> Json.obj()
    )))
    for
      plain     <- counting.handle(call)
      recovered <- counting.handleRecovering(call)
    yield List(plain, recovered).foreach(r => assertEquals(success(r).hcursor.downField("content").downArray.get[String]("text"), Right("counted")))

  test("an empty error message is replaced, since the protocol requires one"):
    assertEquals("".assumeNonEmpty: String, "unspecified error")
    assertEquals("kept".assumeNonEmpty: String, "kept")
