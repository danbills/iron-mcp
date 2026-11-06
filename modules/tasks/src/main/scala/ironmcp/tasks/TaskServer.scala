package ironmcp
package tasks

import cats.{Applicative, ApplicativeThrow}
import cats.effect.{Concurrent, Temporal}
import cats.effect.std.SecureRandom
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.server.{McpException, NotificationSink, ServerHandler}
import ironmcp.tasks.TaskModels.*

/** Server wrapper implementing the Tasks Extension (SEP-2663, Handbook §7.3).
  *
  * Wraps an underlying [[ServerHandler]], advertises `io.modelcontextprotocol/tasks`
  * in capabilities, serves `tasks/get`, `tasks/cancel`, and `tasks/update`, and
  * offloads task-designated tools to background fibers when invoked by task-capable
  * clients, returning `resultType: "task"` immediately.
  *
  * A task ID is the only thing `tasks/get` and `tasks/cancel` check, so it is
  * 128 bits from `random` (a [[SecureRandom]]), never the clock or
  * `scala.util.Random`. The ID is not bound to a caller: over HTTP with several
  * users, anyone holding an ID can read or cancel that task.
  */
final class TaskServer[F[_]: {Temporal, ApplicativeThrow}] (
    val underlying: ServerHandler[F],
    val store: TaskStore[F],
    random: SecureRandom[F],
    val taskTools: Set[String] = Set.empty,
    val defaultPollIntervalMs: Long = 1000L,
    val defaultTtlMs: Long = 300000L
) extends ServerHandler[F]:

  def handle(message: JsonRpcMessage): F[Option[JsonRpcMessage]] =
    handleEmitting(message, _ => Applicative[F].unit)

  /** Requests the extension does not take over pass `emit` through, so their
    * progress reaches the client. A tool run as a background task has no
    * request left to report on, so it gets no sink.
    */
  override def handleEmitting(message: JsonRpcMessage, emit: NotificationSink[F]): F[Option[JsonRpcMessage]] = message match
    case req @ JsonRpcMessage.Request(id, method, params) =>
      method match
        case "tasks/get" =>
          handleGetTask(id, params)

        case "tasks/cancel" =>
          handleCancelTask(id, params)

        case "tasks/update" =>
          handleUpdateTask(id, params)

        case "tools/call" =>
          handleToolsCall(req, emit)

        case Method.Discover.wire =>
          handleDiscover(req)

        case _ =>
          underlying.handleEmitting(req, emit)

    case other =>
      underlying.handleEmitting(other, emit)

  def handleRecovering(message: JsonRpcMessage)(using ApplicativeThrow[F]): F[Option[JsonRpcMessage]] =
    handleEmittingRecovering(message, _ => Applicative[F].unit)

  override def handleEmittingRecovering(message: JsonRpcMessage, emit: NotificationSink[F])(using
      ApplicativeThrow[F]
  ): F[Option[JsonRpcMessage]] =
    handleEmitting(message, emit).handleError { error =>
      val id = message match
        case JsonRpcMessage.Request(id, _, _) => Some(id)
        case _                                => None
      val reply = error match
        case McpException(rpc) => rpc
        case _                 => JsonRpcError(ErrorCode.InternalError, "internal error")
      id.map(requestId => JsonRpcMessage.Failure(Some(requestId), reply))
    }

  private def handleGetTask(id: RequestId, params: Option[Json]): F[Option[JsonRpcMessage]] =
    params.flatMap(_.as[GetTaskParams].toOption) match
      case None =>
        Temporal[F].pure(Some(JsonRpcMessage.Failure(Some(id), JsonRpcError(ErrorCode.InvalidParams, "Invalid tasks/get params"))))
      case Some(p) =>
        store.get(p.taskId).map {
          case Some(snapshot) =>
            Some(JsonRpcMessage.Success(id, snapshot.asJson))
          case None =>
            Some(JsonRpcMessage.Failure(Some(id), JsonRpcError(ErrorCode.InvalidParams, s"Task '${p.taskId}' not found".assume[Not[Empty]])))
        }

  private def handleCancelTask(id: RequestId, params: Option[Json]): F[Option[JsonRpcMessage]] =
    params.flatMap(_.as[CancelTaskParams].toOption) match
      case None =>
        Temporal[F].pure(Some(JsonRpcMessage.Failure(Some(id), JsonRpcError(ErrorCode.InvalidParams, "Invalid tasks/cancel params"))))
      case Some(p) =>
        store.cancel(p.taskId).map {
          case Some(res) =>
            Some(JsonRpcMessage.Success(id, res.asJson))
          case None =>
            Some(JsonRpcMessage.Failure(Some(id), JsonRpcError(ErrorCode.InvalidParams, s"Task '${p.taskId}' not found".assume[Not[Empty]])))
        }

  private def handleUpdateTask(id: RequestId, params: Option[Json]): F[Option[JsonRpcMessage]] =
    params.flatMap(_.as[UpdateTaskParams].toOption) match
      case None =>
        Temporal[F].pure(Some(JsonRpcMessage.Failure(Some(id), JsonRpcError(ErrorCode.InvalidParams, "Invalid tasks/update params"))))
      case Some(p) =>
        store.update(p.taskId, p.inputResponses).map {
          case Some(res) =>
            Some(JsonRpcMessage.Success(id, res.asJson))
          case None =>
            Some(JsonRpcMessage.Failure(Some(id), JsonRpcError(ErrorCode.InvalidParams, s"Task '${p.taskId}' not found".assume[Not[Empty]])))
        }

  private def handleToolsCall(req: JsonRpcMessage.Request, emit: NotificationSink[F]): F[Option[JsonRpcMessage]] =
    val toolName = req.params.flatMap(_.hcursor.get[String]("name").toOption).getOrElse("")
    val clientAcceptsTasks = hasTasksCapability(req.params)

    if taskTools.contains(toolName) && clientAcceptsTasks then
      for
        bytes <- random.nextBytes(16)
        taskId = "task-" + bytes.map(b => f"${b & 0xff}%02x").mkString
        fiber <- Concurrent[F].start {
          underlying.handle(req).flatMap {
            case Some(JsonRpcMessage.Success(_, resJson)) =>
              resJson.as[CallToolResult] match
                case Right(callResult) => store.complete(taskId, callResult)
                case Left(_)           => store.fail(taskId, JsonRpcError(ErrorCode.InternalError, "Tool output malformed"))
            case Some(JsonRpcMessage.Failure(_, err)) =>
              store.fail(taskId, err)
            case _ =>
              store.fail(taskId, JsonRpcError(ErrorCode.InternalError, "Tool produced no result"))
          }.handleErrorWith { err =>
            val msg = Option(err.getMessage).filter(_.nonEmpty).getOrElse("internal error")
            store.fail(taskId, JsonRpcError(ErrorCode.InternalError, msg.assume[Not[Empty]]))
          }
        }
        createRes <- store.create(taskId, defaultPollIntervalMs, defaultTtlMs, fiber.cancel)
      yield Some(JsonRpcMessage.Success(req.id, createRes.asJson))
    else
      // Plain synchronous execution for non-task tools or non-task clients
      underlying.handleEmitting(req, emit)

  private def handleDiscover(req: JsonRpcMessage.Request): F[Option[JsonRpcMessage]] =
    underlying.handle(req).map {
      case Some(JsonRpcMessage.Success(id, json)) =>
        // Inject io.modelcontextprotocol/tasks into server capabilities
        val tasksExt = Json.obj(CapabilityName -> Json.obj())
        val updated = json.hcursor.downField("capabilities").downField("extensions").withFocus {
          case ex if ex.isObject => ex.deepMerge(tasksExt)
          case _                 => tasksExt
        }.top.getOrElse(json)
        Some(JsonRpcMessage.Success(id, updated))
      case other => other
    }

  private def hasTasksCapability(params: Option[Json]): Boolean =
    params.flatMap(_.hcursor.downField("_meta").focus).exists { meta =>
      meta.hcursor.downField("io.modelcontextprotocol/clientCapabilities").downField("extensions").downField(CapabilityName).succeeded ||
      meta.hcursor.downField("clientCapabilities").downField("extensions").downField(CapabilityName).succeeded ||
      meta.hcursor.downField(CapabilityName).succeeded
    }

object TaskServer:

  def apply[F[_]: {Temporal, ApplicativeThrow}](
      underlying: ServerHandler[F],
      store: TaskStore[F],
      random: SecureRandom[F],
      taskTools: Set[String] = Set.empty,
      defaultPollIntervalMs: Long = 1000L,
      defaultTtlMs: Long = 300000L
  ): TaskServer[F] =
    new TaskServer[F](underlying, store, random, taskTools, defaultPollIntervalMs, defaultTtlMs)
