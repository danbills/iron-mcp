package ironmcp
package tasks

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.SecureRandom
import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.{ClientTransport, InputHandlers, McpClient, McpClientError}
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.tasks.TaskModels.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** A tool running as a task asks its caller mid-flight and carries on with the answer. */
class TaskInputSuite extends CatsEffectSuite:

  final case class Order(item: String :| Not[Empty]) derives Decoder, JsonSchemaOf
  final case class Confirm(confirm: Boolean) derives Decoder, JsonSchemaOf

  /** `checkout` reserves (counted), asks to confirm, then buys or releases; `gate` holds it before asking. */
  private def setup(gate: Option[Deferred[IO, Unit]] = None) =
    for
      reserved <- Ref[IO].of(0)
      random   <- SecureRandom.javaSecuritySecureRandom[IO]
      store    <- TaskStore.inMemory[IO](defaultPollIntervalMs = 5L)
      checkout = TaskTool[IO, Order]("checkout", "Reserves an item, asks, then buys it.") { (order, task) =>
                   val item: String = order.item
                   for
                     _      <- reserved.update(_ + 1)
                     _      <- task.status(s"reserved $item")
                     _      <- gate.fold(IO.unit)(_.get)
                     answer <- task.ask(Map("confirm" -> Elicit.form[Confirm](s"Buy $item?")))
                     yes     = answer.get("confirm").flatMap(_.as[ElicitResult].toOption)
                                 .exists(r => r.action == ElicitAction.Accept && r.content.flatMap(_("confirm")).flatMap(_.asBoolean).contains(true))
                   yield if yes then s"bought $item" else s"released $item"
                 }
      base   = McpServer[IO](Implementation("shop", "0"), tools = Some(ToolSet.of(checkout.mcpTool)))
      server = TaskServer[IO](base, store, random, defaultPollIntervalMs = 5L, inputTools = List(checkout))
    yield (server, store, reserved)

  private def confirming(yes: Boolean) = InputHandlers.elicitation[IO](_ =>
    IO.pure(ElicitResult(ElicitAction.Accept, Some(JsonObject("confirm" -> yes.asJson))))
  )

  private val book = JsonObject("item" -> "lamp".asJson)

  private def text(r: CallToolResult) = r.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString

  test("the task asks, the client answers through tasks/update, and the task finishes without starting over") {
    for
      (server, _, reserved) <- setup()
      client                <- TasksClient[IO](ClientTransport.inProcess(server), handlers = confirming(true))
      result                <- client.callToolWithPolling("checkout", book, defaultPollInterval = 5.millis)
      count                 <- reserved.get
    yield
      assertEquals(text(result), "bought lamp")
      assertEquals(count, 1)
  }

  test("a declined answer reaches the task") {
    for
      (server, _, _) <- setup()
      client         <- TasksClient[IO](ClientTransport.inProcess(server), handlers = confirming(false))
      result         <- client.callToolWithPolling("checkout", book, defaultPollInterval = 5.millis)
    yield assertEquals(text(result), "released lamp")
  }

  test("while it waits, tasks/get shows input_required, the requests keyed as asked, and its status message") {
    for
      (server, _, _) <- setup()
      client         <- TasksClient[IO](ClientTransport.inProcess(server))
      created        <- client.callToolAsTask("checkout", book)
      id              = created match
                          case c: CreateTaskResult => c.taskId
                          case other               => fail(s"expected a task, got $other")
      waiting        <- client.getTask(id).iterateUntil(_.status == TaskStatus.InputRequired).timeout(2.seconds)
      _              <- client.updateTask(id, Map("confirm" -> ElicitResult(ElicitAction.Accept, Some(JsonObject("confirm" -> true.asJson))).asJson))
      done           <- client.getTask(id).iterateUntil(_.status.isTerminal).timeout(2.seconds)
    yield
      val asked = waiting.inputRequests.getOrElse(Map.empty)
      assertEquals(asked.keySet, Set("confirm"))
      assertEquals(asked("confirm").hcursor.get[String]("method"), Right("elicitation/create"))
      assertEquals(waiting.statusMessage, Some("reserved lamp"))
      assertEquals(done.status, TaskStatus.Completed)
      assertEquals(done.result.map(text), Some("bought lamp"))
      assertEquals(done.inputRequests, None)
  }

  test("a client that does not declare tasks runs it in the request, where asking is a tool error") {
    for
      (server, _, _) <- setup()
      client         <- McpClient[IO](ClientTransport.inProcess(server))
      result         <- client.callTool("checkout", book)
    yield result match
      case r: CallToolResult =>
        assertEquals(r.isError, Some(true))
        assert(text(r).contains("needs a client that runs it as a task"), text(r))
      case other => fail(s"unexpected $other")
  }

  test("a client with no handler for the request fails the call") {
    for
      (server, _, _) <- setup()
      client         <- TasksClient[IO](ClientTransport.inProcess(server))
      result         <- client.callToolWithPolling("checkout", book, defaultPollInterval = 5.millis).attempt
    yield assert(result.left.exists(_.isInstanceOf[McpClientError.UnsupportedInputRequest]), result)
  }

  test("cancelling a task that waits for input stops it") {
    for
      (server, store, _) <- setup()
      client             <- TasksClient[IO](ClientTransport.inProcess(server))
      created            <- client.callToolAsTask("checkout", book)
      id                  = created match
                              case c: CreateTaskResult => c.taskId
                              case other               => fail(s"expected a task, got $other")
      _                  <- client.getTask(id).iterateUntil(_.status == TaskStatus.InputRequired).timeout(2.seconds)
      cancelled          <- client.cancelTask(id)
      _                  <- IO.sleep(20.millis)
      after              <- store.get(id, None)
    yield
      assertEquals(cancelled.status, TaskStatus.Cancelled)
      assertEquals(after.map(_.status), Some(TaskStatus.Cancelled))
  }

  test("asking after the task is over fails with TaskInputUnavailable") {
    for
      store  <- TaskStore.inMemory[IO]()
      _      <- store.create("t-over", 10L, 10000L, IO.unit, None)
      _      <- store.complete("t-over", CallToolResult.text("done"))
      task   <- TaskContext[IO]("t-over", store)
      asked  <- task.ask(Map("x" -> InputRequest.listRoots)).attempt
      gone   <- store.awaitInput("missing", Map.empty)
      _      <- task.status("ignored")
      still  <- store.get("t-over", None)
    yield
      assert(asked.left.exists(_.isInstanceOf[TaskInputUnavailable]), asked)
      assertEquals(gone, None)
      assertEquals(still.flatMap(_.statusMessage), None)
  }

  test("an answer to a task nothing waits on only sets it working again") {
    for
      store <- TaskStore.inMemory[IO]()
      _     <- store.create("t-x", 10L, 10000L, IO.unit, None)
      _     <- store.requireInput("t-x", Map("k" -> Json.obj()))
      upd   <- store.update("t-x", Map("k" -> Json.True), None)
    yield assertEquals(upd.map(_.status), Some(TaskStatus.Working))
  }

  test("a plain tool's input_required result is reported, not lost") {
    val result = TaskTool.complete(InputRequiredResult(inputRequests = Some(Map.empty)))
    assertEquals(result.isError, Some(true))
  }

  test("discover adds the tasks extension when the server declares no extensions") {
    for
      (server, _, _) <- setup()
      reply          <- server.handle(JsonRpcMessage.Request(RequestId.Num(1), "server/discover", Some(Json.obj("_meta" ->
                          RequestMeta(LatestProtocolVersion, ClientCapabilities()).asJson))))
    yield reply match
      case Some(JsonRpcMessage.Success(_, json)) =>
        assert(json.hcursor.downField("capabilities").downField("extensions").downField(CapabilityName).succeeded, json)
        assert(json.hcursor.downField("capabilities").downField("tools").succeeded, json)
      case other => fail(s"unexpected $other")
  }
