package ironmcp
package tasks

import cats.MonadThrow
import cats.effect.{Ref, Temporal}
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.{ClientTransport, McpClient, McpClientError}
import ironmcp.protocol.*
import ironmcp.tasks.TaskModels.*

import scala.concurrent.duration.*

final case class TaskCancelledException(taskId: String)
    extends Exception(s"Task '$taskId' was cancelled")

/** Client wrapper implementing the Tasks Extension client-side methods (§7.3).
  *
  * Supports:
  * 1. `callToolAsTask`: declares the extension, returning `CallToolResult | CreateTaskResult`.
  * 2. `getTask`: polls task snapshot.
  * 3. `cancelTask`: requests cooperative task cancellation.
  * 4. `updateTask`: supplies mid-flight input responses.
  * 5. `callToolWithPolling`: the transparent path (§7.3), declaring the extension and
  *    polling any background task handle to completion internally.
  */
final class TasksClient[F[_]: {Temporal, MonadThrow}] (
    val client: McpClient[F],
    transport: ClientTransport[F],
    val info: Implementation,
    ids: Ref[F, Long]
):

  private val tasksCapabilities: ClientCapabilities =
    ClientCapabilities(
      extensions = Some(Map(CapabilityName -> JsonObject.empty))
    )

  private val metaJson: Json =
    RequestMeta(LatestProtocolVersion, tasksCapabilities, Some(info)).asJson

  /** Sends a request with `io.modelcontextprotocol/tasks` capability declared in `_meta`. */
  def request[R: Decoder](method: String, params: JsonObject = JsonObject.empty): F[R] =
    for
      id <- ids.updateAndGet(_ + 1)
      methodName <- MonadThrow[F].fromEither(
        method.refineEither[Not[Empty]].leftMap(_ => McpClientError.Malformed("empty method name"))
      )
      message = new JsonRpcMessage.Request(
        RequestId.Num(id),
        methodName,
        Some(Json.fromJsonObject(params.add("_meta", metaJson)))
      )
      reply <- transport.request(message)
      result <- reply match
        case JsonRpcMessage.Success(_, json) =>
          val completedJson = json.mapObject(obj => if obj.contains("resultType") then obj else obj.add("resultType", Json.fromString("complete")))
          MonadThrow[F].fromEither(
            completedJson.as[R].leftMap(f => McpClientError.Malformed(s"$method: ${f.getMessage}"))
          )
        case JsonRpcMessage.Failure(_, error) =>
          MonadThrow[F].raiseError(McpClientError.Rpc(error))
        case other =>
          MonadThrow[F].raiseError(McpClientError.Malformed(s"$method: unexpected $other"))
    yield result

  /** Declares the tasks extension and returns either an immediate result or a task handle. */
  def callToolAsTask(
      name: String,
      arguments: JsonObject = JsonObject.empty
  ): F[ToolCallOrTask] =
    val params = JsonObject("name" -> name.asJson, "arguments" -> Json.fromJsonObject(arguments))
    request[Json]("tools/call", params).flatMap { json =>
      val resultType = json.hcursor.get[Option[String]]("resultType").toOption.flatten
      if resultType.contains("task") then
        MonadThrow[F].fromEither(json.as[CreateTaskResult].leftMap(f => McpClientError.Malformed(s"tools/call: ${f.getMessage}")))
      else
        MonadThrow[F].fromEither(json.as[CallToolResult].leftMap(f => McpClientError.Malformed(s"tools/call: ${f.getMessage}")))
    }

  /** Polls the status of an ongoing task. */
  def getTask(taskId: String): F[GetTaskResult] =
    request[GetTaskResult]("tasks/get", JsonObject("taskId" -> taskId.asJson))

  /** Requests cooperative cancellation of a background task. */
  def cancelTask(taskId: String): F[CancelTaskResult] =
    request[CancelTaskResult]("tasks/cancel", JsonObject("taskId" -> taskId.asJson))

  /** Supplies mid-flight input responses for a task in `input_required` state. */
  def updateTask(taskId: String, inputResponses: Map[String, Json] = Map.empty): F[UpdateTaskResult] =
    request[UpdateTaskResult](
      "tasks/update",
      JsonObject("taskId" -> taskId.asJson, "inputResponses" -> inputResponses.asJson)
    )

  /** The transparent path (§7.3): declares the tasks extension and polls any
    * background task handle to completion internally, hiding the polling loop behind one call.
    */
  def callToolWithPolling(
      name: String,
      arguments: JsonObject = JsonObject.empty,
      defaultPollInterval: FiniteDuration = 100.millis,
      maxPolls: Int = 300
  ): F[CallToolResult] =
    callToolAsTask(name, arguments).flatMap {
      case res: CallToolResult =>
        Temporal[F].pure(res)
      case created: CreateTaskResult =>
        pollUntilTerminal(
          created.taskId,
          created.pollIntervalMs.map(ms => ms.millis).getOrElse(defaultPollInterval),
          maxPolls
        )
    }

  private def pollUntilTerminal(
      taskId: String,
      interval: FiniteDuration,
      pollsLeft: Int
  ): F[CallToolResult] =
    if pollsLeft <= 0 then
      MonadThrow[F].raiseError(McpClientError.RetryBudgetExhausted(300))
    else
      Temporal[F].sleep(interval) *> getTask(taskId).flatMap { snapshot =>
        snapshot.status match
          case TaskStatus.Working | TaskStatus.InputRequired =>
            val nextInterval = snapshot.pollIntervalMs.map(ms => ms.millis).getOrElse(interval)
            pollUntilTerminal(taskId, nextInterval, pollsLeft - 1)
          case TaskStatus.Completed =>
            snapshot.result match
              case Some(callResult) => MonadThrow[F].pure(callResult)
              case None =>
                MonadThrow[F].raiseError(
                  McpClientError.Malformed(s"Task '$taskId' reached status completed without an inlined result")
                )
          case TaskStatus.Failed =>
            val err = snapshot.error.getOrElse(JsonRpcError(ErrorCode.InternalError, s"Task '$taskId' failed".assume[Not[Empty]]))
            MonadThrow[F].raiseError(McpClientError.Rpc(err))
          case TaskStatus.Cancelled =>
            MonadThrow[F].raiseError(TaskCancelledException(taskId))
      }

object TasksClient:

  def apply[F[_]: {Temporal, MonadThrow}](
      transport: ClientTransport[F],
      info: Implementation = Implementation(name = "iron-mcp-tasks-client", version = "1.0.0")
  ): F[TasksClient[F]] =
    for
      client <- McpClient[F](transport, info)
      ids <- Ref.of[F, Long](0L)
    yield new TasksClient[F](client, transport, info, ids)
