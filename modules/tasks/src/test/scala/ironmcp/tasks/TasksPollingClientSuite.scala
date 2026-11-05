package ironmcp
package tasks

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.{ClientTransport, McpClientError}
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.tasks.TaskModels.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class TasksPollingClientSuite extends CatsEffectSuite:

  final case class SlowArgs(delayMs: Int, message: String) derives Decoder, JsonSchemaOf

  private val slowTool = McpTool[SlowArgs]("slow_work", "Does work with simulated delay.") { args =>
    IO.sleep(args.delayMs.millis).as(s"Processed: ${args.message}")
  }

  private val failingTool = McpTool[SlowArgs]("failing_work", "Always fails after delay.") { args =>
    IO.sleep(args.delayMs.millis) *> IO.raiseError[String](new RuntimeException(s"Failure: ${args.message}"))
  }

  private val syncTool = McpTool[SlowArgs]("sync_work", "Synchronous instant work.") { args =>
    IO.pure(s"Instant: ${args.message}")
  }

  private def setupClientAndServer(): IO[(TasksClient[IO], TaskServer[IO])] =
    for
      store <- TaskStore.inMemory[IO](defaultPollIntervalMs = 20L)
      baseServer = McpServer[IO](
        info = Implementation(name = "test-tasks-server", version = "1.0.0"),
        tools = Some(ToolSet.of(slowTool, failingTool, syncTool))
      )
      taskServer = TaskServer[IO](
        underlying = baseServer,
        store = store,
        taskTools = Set("slow_work", "failing_work"),
        defaultPollIntervalMs = 20L
      )
      transport = ClientTransport.inProcess[IO](taskServer)
      client <- TasksClient[IO](transport)
    yield (client, taskServer)

  test("callToolAsTask returns CreateTaskResult and getTask polls to completion"):
    for
      (client, _) <- setupClientAndServer()
      res <- client.callToolAsTask(
        "slow_work",
        JsonObject("delayMs" -> 50.asJson, "message" -> "hello async".asJson)
      )
      created = res match
        case c: CreateTaskResult => c
        case other               => fail(s"Expected CreateTaskResult, got $other")
      _ = assertEquals(created.status, TaskStatus.Working)

      // Poll until completed
      finalSnap <- IO.sleep(100.millis) *> client.getTask(created.taskId)
      _ = assertEquals(finalSnap.status, TaskStatus.Completed)
      _ = assert(finalSnap.result.isDefined)
      text = finalSnap.result.get.content.headOption.map(_.asInstanceOf[ContentBlock.Text].text)
      _ = assertEquals(text, Some("Processed: hello async".assume[Not[Empty]]))
    yield ()

  test("callToolWithPolling hides the polling loop behind transparent execution"):
    for
      (client, _) <- setupClientAndServer()
      result <- client.callToolWithPolling(
        "slow_work",
        JsonObject("delayMs" -> 40.asJson, "message" -> "transparent poll".asJson),
        defaultPollInterval = 20.millis
      )
      text = result.content.headOption.map(_.asInstanceOf[ContentBlock.Text].text)
      _ = assertEquals(text, Some("Processed: transparent poll".assume[Not[Empty]]))
    yield ()

  test("callToolWithPolling on synchronous non-task tool returns immediately"):
    for
      (client, _) <- setupClientAndServer()
      result <- client.callToolWithPolling(
        "sync_work",
        JsonObject("delayMs" -> 0.asJson, "message" -> "sync value".asJson)
      )
      text = result.content.headOption.map(_.asInstanceOf[ContentBlock.Text].text)
      _ = assertEquals(text, Some("Instant: sync value".assume[Not[Empty]]))
    yield ()

  test("cancelTask cooperatively cancels the background fiber"):
    for
      (client, _) <- setupClientAndServer()
      res <- client.callToolAsTask(
        "slow_work",
        JsonObject("delayMs" -> 5000.asJson, "message" -> "will be cancelled".asJson)
      )
      created = res.asInstanceOf[CreateTaskResult]

      // Cancel it
      cancelRes <- client.cancelTask(created.taskId)
      _ = assertEquals(cancelRes.status, TaskStatus.Cancelled)

      // getTask reflects Cancelled state
      snap <- client.getTask(created.taskId)
      _ = assertEquals(snap.status, TaskStatus.Cancelled)
    yield ()

  test("callToolWithPolling on a failing tool returns CallToolResult with isError = true"):
    for
      (client, _) <- setupClientAndServer()
      result <- client.callToolWithPolling(
        "failing_work",
        JsonObject("delayMs" -> 20.asJson, "message" -> "boom".asJson),
        defaultPollInterval = 10.millis
      )
      _ = assertEquals(result.isError, Some(true))
      text = result.content.headOption.map(_.asInstanceOf[ContentBlock.Text].text)
      _ = assert(text.exists(_.contains("boom")))
    yield ()

  test("getTask on non-existent taskId raises McpClientError.Rpc with InvalidParams"):
    for
      (client, _) <- setupClientAndServer()
      attempt <- client.getTask("no-such-task").attempt
      _ = assert(attempt.isLeft)
      _ = attempt.left.toOption.get match
        case McpClientError.Rpc(err) => assertEquals(err.code, ErrorCode.InvalidParams)
        case other                   => fail(s"Expected McpClientError.Rpc, got $other")
    yield ()
