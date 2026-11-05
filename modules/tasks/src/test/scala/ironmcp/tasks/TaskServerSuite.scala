package ironmcp
package tasks

import cats.effect.IO
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

  private def setupServer: IO[TaskServer[IO]] =
    for
      store <- TaskStore.inMemory[IO](defaultPollIntervalMs = 50L)
      baseServer = McpServer[IO](
        info = serverInfo,
        tools = Some(ToolSet.of(slowReportTool, syncEchoTool))
      )
      taskServer = TaskServer[IO](
        underlying = baseServer,
        store = store,
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
      json = res.get.asInstanceOf[JsonRpcMessage.Success].result
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
      json = res.get.asInstanceOf[JsonRpcMessage.Success].result
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
      json = res.get.asInstanceOf[JsonRpcMessage.Success].result
      _ = assertEquals(json.hcursor.get[String]("resultType"), Right("task"))
      taskId = json.hcursor.get[String]("taskId").toOption.get
      _ = assertEquals(json.hcursor.get[TaskStatus]("status"), Right(TaskStatus.Working))

      // Wait a moment for the background fiber to complete
      _ <- IO.sleep(200.millis)

      // Poll tasks/get
      getReq = JsonRpcMessage.Request(
        id = RequestId.Num(4L),
        method = "tasks/get",
        params = Some(Json.obj("taskId" -> taskId.asJson, "_meta" -> tasksMeta))
      )
      getRes <- server.handle(getReq)
      _ = assert(getRes.isDefined)
      getJson = getRes.get.asInstanceOf[JsonRpcMessage.Success].result
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
      taskId = res.get.asInstanceOf[JsonRpcMessage.Success].result.hcursor.get[String]("taskId").toOption.get

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
      getJson = getRes.get.asInstanceOf[JsonRpcMessage.Success].result
      _ = assertEquals(getJson.hcursor.get[TaskStatus]("status"), Right(TaskStatus.Cancelled))
    yield ()
