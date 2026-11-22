package ironmcp
package tasks

import cats.effect.IO
import cats.effect.std.SecureRandom
import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.tasks.TaskModels.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class TaskServerSuite extends CatsEffectSuite:

  final case class SlowReportArgs(seconds: Int) derives Decoder, JsonSchemaOf

  private val slowReportTool = McpTool[SlowReportArgs]("generate_report", "Generates report slowly.") { args =>
    IO.sleep(args.seconds.seconds).as(s"Report generated after ${args.seconds}s.")
  }

  private val syncEchoTool = McpTool[SlowReportArgs]("quick_echo", "Fast echo.") { args =>
    IO.pure(s"Echo: ${args.seconds}")
  }

  private val serverInfo = Implementation(name = "test-tasks-server", version = "1.0.0")

  private val baseMeta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities()
  ).asJson

  private val tasksMeta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities(
      extensions = Some(Map(CapabilityName -> JsonObject.empty))
    )
  ).asJson

  /** The result of a reply that succeeded; anything else fails the test. */
  private def resultOf(reply: Option[JsonRpcMessage]): Json = reply match
    case Some(JsonRpcMessage.Success(_, result)) => result
    case other                                    => fail(s"expected a result, got $other")

  private def taskIdOf(reply: Option[JsonRpcMessage]): String =
    resultOf(reply).hcursor.get[String]("taskId").fold(fail("the result has no taskId", _), identity)

  private def setupServer: IO[TaskServer[IO]] =
    for
      random <- SecureRandom.javaSecuritySecureRandom[IO]
      store <- TaskStore.inMemory[IO](defaultPollIntervalMs = 50L)
      baseServer = McpServer[IO](
        info = serverInfo,
        tools = Some(ToolSet.of(slowReportTool, syncEchoTool))
      )
      taskServer = TaskServer[IO](
        underlying = baseServer,
        store = store,
        random = random,
        taskTools = Set("generate_report"),
        defaultPollIntervalMs = 50L
      )
    yield taskServer

  test("server/discover advertises io.modelcontextprotocol/tasks capability"):
    for
      server <- setupServer
      req = JsonRpcMessage.Request(
        id = RequestId.Num(1L),
        method = "server/discover",
        params = Some(Json.obj("_meta" -> baseMeta))
      )
      res <- server.handle(req)
      _ = assert(res.isDefined)
      json = resultOf(res)
      extensions = json.hcursor.downField("capabilities").downField("extensions")
      _ = assert(extensions.downField("io.modelcontextprotocol/tasks").succeeded)
    yield ()

  test("plain tool call from client without tasks capability executes synchronously"):
    for
      server <- setupServer
      req = JsonRpcMessage.Request(
        id = RequestId.Num(2L),
        method = "tools/call",
        params = Some(
          Json.obj(
            "name" -> "quick_echo".asJson,
            "arguments" -> Json.obj("seconds" -> 42.asJson),
            "_meta" -> baseMeta
          )
        )
      )
      res <- server.handle(req)
      _ = assert(res.isDefined)
      json = resultOf(res)
      _ = assertEquals(json.hcursor.get[String]("resultType"), Right("complete"))
      content = json.hcursor.downField("content").downArray.get[String]("text")
      _ = assertEquals(content, Right("Echo: 42"))
    yield ()

  test("task tool call from task-capable client returns CreateTaskResult and completes in background"):
    for
      server <- setupServer
      req = JsonRpcMessage.Request(
        id = RequestId.Num(3L),
        method = "tools/call",
        params = Some(
          Json.obj(
            "name" -> "generate_report".asJson,
            "arguments" -> Json.obj("seconds" -> 0.asJson),
            "_meta" -> tasksMeta
          )
        )
      )
      res <- server.handle(req)
      _ = assert(res.isDefined)
      json = resultOf(res)
      _ = assertEquals(json.hcursor.get[String]("resultType"), Right("task"))
      taskId = json.hcursor.get[String]("taskId").fold(fail("the result has no taskId", _), identity)
      _ = assertEquals(json.hcursor.get[TaskStatus]("status"), Right(TaskStatus.Working))

      // Wait a moment for the background fiber to complete
      _ <- IO.sleep(500.millis)

      // Poll tasks/get
      getReq = JsonRpcMessage.Request(
        id = RequestId.Num(4L),
        method = "tasks/get",
        params = Some(Json.obj("taskId" -> taskId.asJson, "_meta" -> tasksMeta))
      )
      getRes <- server.handle(getReq)
      _ = assert(getRes.isDefined)
      getJson = resultOf(getRes)
      _ = assertEquals(getJson.hcursor.get[TaskStatus]("status"), Right(TaskStatus.Completed))
      text = getJson.hcursor.downField("result").downField("content").downArray.get[String]("text")
      _ = assertEquals(text, Right("Report generated after 0s."))
    yield ()

  test("cooperative cancellation cancels the background task"):
    for
      server <- setupServer
      req = JsonRpcMessage.Request(
        id = RequestId.Num(5L),
        method = "tools/call",
        params = Some(
          Json.obj(
            "name" -> "generate_report".asJson,
            "arguments" -> Json.obj("seconds" -> 60.asJson), // slow task (60s)
            "_meta" -> tasksMeta
          )
        )
      )
      res <- server.handle(req)
      taskId = taskIdOf(res)

      // Request cancellation
      cancelReq = JsonRpcMessage.Request(
        id = RequestId.Num(6L),
        method = "tasks/cancel",
        params = Some(Json.obj("taskId" -> taskId.asJson, "_meta" -> tasksMeta))
      )
      cancelRes <- server.handle(cancelReq)
      _ = assert(cancelRes.isDefined)

      // Poll tasks/get to verify status is Cancelled
      getReq = JsonRpcMessage.Request(
        id = RequestId.Num(7L),
        method = "tasks/get",
        params = Some(Json.obj("taskId" -> taskId.asJson, "_meta" -> tasksMeta))
      )
      getRes <- server.handle(getReq)
      getJson = resultOf(getRes)
      _ = assertEquals(getJson.hcursor.get[TaskStatus]("status"), Right(TaskStatus.Cancelled))
    yield ()

  test("a background task that throws goes to onUnhandled and fails with -32603, no detail"):
    // An underlying handler that throws, as a provider bug would.
    val throwing = new ServerHandler[IO]:
      def handle(message: JsonRpcMessage): IO[Option[JsonRpcMessage]] = IO.raiseError(RuntimeException("db password is hunter2"))
      def handleRecovering(message: JsonRpcMessage)(using cats.ApplicativeThrow[IO]): IO[Option[JsonRpcMessage]] = handle(message)
    for
      seen   <- IO.ref(List.empty[String])
      random <- SecureRandom.javaSecuritySecureRandom[IO]
      store  <- TaskStore.inMemory[IO](defaultPollIntervalMs = 50L)
      server = TaskServer[IO](
        underlying = throwing,
        store = store,
        random = random,
        taskTools = Set("generate_report"),
        onUnhandled = Some((_, error) => seen.update(error.getMessage :: _))
      )
      res <- server.handle(
        JsonRpcMessage.Request(
          id = RequestId.Num(5L),
          method = "tools/call",
          params = Some(Json.obj("name" -> "generate_report".asJson, "arguments" -> Json.obj("seconds" -> 0.asJson), "_meta" -> tasksMeta))
        )
      )
      taskId = taskIdOf(res)
      _ <- IO.sleep(200.millis)
      getRes <- server.handle(
        JsonRpcMessage.Request(RequestId.Num(6L), "tasks/get", Some(Json.obj("taskId" -> taskId.asJson, "_meta" -> tasksMeta)))
      )
      hooked <- seen.get
    yield
      val snapshot = resultOf(getRes)
      assertEquals(snapshot.hcursor.get[TaskStatus]("status"), Right(TaskStatus.Failed))
      assertEquals(snapshot.hcursor.downField("error").get[Int]("code"), Right(-32603))
      assert(!snapshot.noSpaces.contains("hunter2"))
      assertEquals(hooked, List("db password is hunter2"))

  test("TaskServer handles invalid params, tasks/update, notifications, handleRecovering, and underlying failure shapes"):
    import cats.syntax.all.*
    import io.circe.syntax.*
    val custom = new ServerHandler[IO]:
      def handle(message: JsonRpcMessage): IO[Option[JsonRpcMessage]] = message match
        case JsonRpcMessage.Request(id, "initialize", _) =>
          IO.pure(Some(JsonRpcMessage.Success(id, Json.obj("capabilities" -> Json.obj("extensions" -> Json.obj("other" -> Json.obj()))))))
        case JsonRpcMessage.Request(id, "tools/call", Some(p)) =>
          p.hcursor.get[String]("name").getOrElse("") match
            case "malformed" => IO.pure(Some(JsonRpcMessage.Success(id, Json.obj("bad" -> true.asJson))))
            case "rpc_fail"  => IO.pure(Some(JsonRpcMessage.Failure(Some(id), JsonRpcError(ErrorCode.InvalidParams, "bad arg"))))
            case "empty"     => IO.pure(None)
            case "mcp_err"   => IO.raiseError(ironmcp.server.McpException.resourceNotFound("r"))
            case _           => IO.pure(Some(JsonRpcMessage.Success(id, CallToolResult(Nil).asJson)))
        case _ => IO.pure(None)
      def handleRecovering(message: JsonRpcMessage)(using cats.ApplicativeThrow[IO]): IO[Option[JsonRpcMessage]] = handle(message)

    for
      random <- SecureRandom.javaSecuritySecureRandom[IO]
      store  <- TaskStore.inMemory[IO](defaultPollIntervalMs = 50L)
      server = TaskServer[IO](
        underlying = custom,
        store = store,
        random = random,
        taskTools = Set("malformed", "rpc_fail", "empty", "mcp_err")
      )
      badGet    <- server.handle(JsonRpcMessage.Request(RequestId.Num(1), "tasks/get", Some(Json.obj())))
      badCancel <- server.handle(JsonRpcMessage.Request(RequestId.Num(2), "tasks/cancel", Some(Json.obj())))
      missCancel<- server.handle(JsonRpcMessage.Request(RequestId.Num(3), "tasks/cancel", Some(Json.obj("taskId" -> "nope".asJson))))
      badUpdate <- server.handle(JsonRpcMessage.Request(RequestId.Num(4), "tasks/update", Some(Json.obj())))
      missUpdate<- server.handle(JsonRpcMessage.Request(RequestId.Num(5), "tasks/update", Some(Json.obj("taskId" -> "nope".asJson, "inputResponses" -> Json.obj()))))
      _         <- store.create("t-upd", 50L, 10000L, IO.unit, None)
      _         <- store.requireInput("t-upd", Map.empty)
      okUpdate  <- server.handle(JsonRpcMessage.Request(RequestId.Num(6), "tasks/update", Some(Json.obj("taskId" -> "t-upd".asJson, "inputResponses" -> Json.obj()))))
      disc      <- server.handleRecovering(JsonRpcMessage.Request(RequestId.Num(7), "initialize", None))
      notif     <- server.handle(JsonRpcMessage.Notification("notifications/cancelled", None))
      otherReq  <- server.handle(JsonRpcMessage.Request(RequestId.Num(8), "ping", None))
      r1 <- server.handle(JsonRpcMessage.Request(RequestId.Num(9), "tools/call", Some(Json.obj("name" -> "malformed".asJson, "_meta" -> tasksMeta))))
      r2 <- server.handle(JsonRpcMessage.Request(RequestId.Num(10), "tools/call", Some(Json.obj("name" -> "rpc_fail".asJson, "_meta" -> tasksMeta))))
      r3 <- server.handle(JsonRpcMessage.Request(RequestId.Num(11), "tools/call", Some(Json.obj("name" -> "empty".asJson, "_meta" -> tasksMeta))))
      r4 <- server.handle(JsonRpcMessage.Request(RequestId.Num(12), "tools/call", Some(Json.obj("name" -> "mcp_err".asJson, "_meta" -> tasksMeta))))
      _  <- IO.sleep(150.millis)
      tIds = List(r1, r2, r3, r4).map(taskIdOf(_))
      snaps <- tIds.traverse(id => store.get(id, None))
    yield
      assert(badGet.exists(_.isInstanceOf[JsonRpcMessage.Failure]))
      assert(badCancel.exists(_.isInstanceOf[JsonRpcMessage.Failure]))
      assert(missCancel.exists(_.isInstanceOf[JsonRpcMessage.Failure]))
      assert(badUpdate.exists(_.isInstanceOf[JsonRpcMessage.Failure]))
      assert(missUpdate.exists(_.isInstanceOf[JsonRpcMessage.Failure]))
      assert(okUpdate.exists(_.isInstanceOf[JsonRpcMessage.Success]))
      assert(resultOf(disc).hcursor.downField("capabilities").downField("extensions").downField("other").succeeded)
      assertEquals(notif, None)
      assertEquals(otherReq, None)
      assert(snaps.forall(_.exists(_.status == TaskStatus.Failed)))

  test("TaskServer defaults, discover without extensions, and discover failure passthrough"):
    val minimal = new ServerHandler[IO]:
      def handle(message: JsonRpcMessage): IO[Option[JsonRpcMessage]] = message match
        case JsonRpcMessage.Request(id, m, _) if (m: String) == Method.Discover.wire =>
          IO.pure(Some(JsonRpcMessage.Success(id, Json.obj("capabilities" -> Json.obj()))))
        case _ => IO.pure(None)
      def handleRecovering(message: JsonRpcMessage)(using cats.ApplicativeThrow[IO]): IO[Option[JsonRpcMessage]] = handle(message)
    for
      random <- SecureRandom.javaSecuritySecureRandom[IO]
      store  <- TaskStore.inMemory[IO]()
      srv    = TaskServer[IO](minimal, store, random)
      d1     <- srv.handle(JsonRpcMessage.Request(RequestId.Num(1), "server/discover", None))
      d2     <- srv.handleRecovering(JsonRpcMessage.Request(RequestId.Num(2), "ping", None))
    yield
      assert(resultOf(d1).hcursor.downField("capabilities").downField("extensions").downField(CapabilityName).succeeded)
      assertEquals(d2, None)
