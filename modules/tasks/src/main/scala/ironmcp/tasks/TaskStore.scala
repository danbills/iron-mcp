package ironmcp
package tasks

import cats.effect.{Ref, Temporal}
import cats.syntax.all.*
import io.circe.Json
import ironmcp.protocol.{CallToolResult, JsonRpcError}
import ironmcp.tasks.TaskModels.*

/** Storage and state machine for background MCP tasks.
  *
  * A task may have an owner: the caller that started it (over HTTP with
  * authorization, its token's subject). `get`, `cancel` and `update` are
  * answered only for that same owner; for anyone else the task does not
  * exist, so a task ID alone is not enough to read or cancel someone else's
  * work. A task created without an owner is reachable by anyone holding its
  * ID.
  */
trait TaskStore[F[_]]:
  def get(taskId: String, owner: Option[String]): F[Option[GetTaskResult]]
  def cancel(taskId: String, owner: Option[String]): F[Option[CancelTaskResult]]
  def update(taskId: String, inputResponses: Map[String, Json], owner: Option[String]): F[Option[UpdateTaskResult]]
  def create(taskId: String, pollIntervalMs: Long, ttlMs: Long, cancelHook: F[Unit], owner: Option[String]): F[CreateTaskResult]
  def complete(taskId: String, result: CallToolResult): F[Unit]
  def fail(taskId: String, error: JsonRpcError): F[Unit]
  def requireInput(taskId: String, inputRequest: Json): F[Unit]

object TaskStore:

  private final case class TaskEntry[F[_]](
      taskId: String,
      status: TaskStatus,
      pollIntervalMs: Long,
      ttlMs: Long,
      createdAt: Long,
      lastUpdatedAt: Long,
      result: Option[CallToolResult] = None,
      error: Option[JsonRpcError] = None,
      inputRequest: Option[Json] = None,
      cancelHook: F[Unit],
      owner: Option[String] = None
  ):
    /** An owned task is visible to its owner only. */
    def visibleTo(caller: Option[String]): Boolean = owner.forall(o => caller.contains(o))

    /** Past its TTL, counted from its last change. */
    def expired(now: Long): Boolean = now - lastUpdatedAt > ttlMs / 1000

    def toGetResult: GetTaskResult =
      GetTaskResult(
        taskId = taskId,
        status = status,
        pollIntervalMs = Some(pollIntervalMs),
        ttlMs = Some(ttlMs),
        createdAt = Some(createdAt),
        lastUpdatedAt = Some(lastUpdatedAt),
        result = result,
        error = error,
        inputRequest = inputRequest
      )

  /** Creates an in-memory task store suitable for single-node servers and testing. */
  def inMemory[F[_]: Temporal](
      defaultPollIntervalMs: Long = 1000L,
      defaultTtlMs: Long = 300000L
  ): F[TaskStore[F]] =
    Ref.of[F, Map[String, TaskEntry[F]]](Map.empty).map { tasksRef =>
      new TaskStore[F]:

        private def nowSeconds: F[Long] =
          Temporal[F].realTime.map(_.toSeconds)

        def get(taskId: String, owner: Option[String]): F[Option[GetTaskResult]] =
          tasksRef.get.map(_.get(taskId).filter(_.visibleTo(owner)).map(_.toGetResult))

        def create(taskId: String, pollIntervalMs: Long, ttlMs: Long, cancelHook: F[Unit], owner: Option[String]): F[CreateTaskResult] =
          nowSeconds.flatMap { now =>
            val entry = TaskEntry[F](
              taskId = taskId,
              status = TaskStatus.Working,
              pollIntervalMs = if pollIntervalMs > 0 then pollIntervalMs else defaultPollIntervalMs,
              ttlMs = if ttlMs > 0 then ttlMs else defaultTtlMs,
              createdAt = now,
              lastUpdatedAt = now,
              cancelHook = cancelHook,
              owner = owner
            )
            // Finished tasks past their TTL go, so the map cannot grow without bound.
            tasksRef.update(m => m.filterNot((_, e) => e.status.isTerminal && e.expired(now)).updated(taskId, entry)).as(
              CreateTaskResult(
                resultType = "task",
                taskId = taskId,
                status = TaskStatus.Working,
                pollIntervalMs = Some(entry.pollIntervalMs),
                ttlMs = Some(entry.ttlMs)
              )
            )
          }

        def complete(taskId: String, result: CallToolResult): F[Unit] =
          nowSeconds.flatMap { now =>
            tasksRef.update { map =>
              map.get(taskId) match
                case Some(e) if !e.status.isTerminal =>
                  map.updated(taskId, e.copy(status = TaskStatus.Completed, result = Some(result), lastUpdatedAt = now))
                case _ => map
            }
          }

        def fail(taskId: String, error: JsonRpcError): F[Unit] =
          nowSeconds.flatMap { now =>
            tasksRef.update { map =>
              map.get(taskId) match
                case Some(e) if !e.status.isTerminal =>
                  map.updated(taskId, e.copy(status = TaskStatus.Failed, error = Some(error), lastUpdatedAt = now))
                case _ => map
            }
          }

        def requireInput(taskId: String, inputRequest: Json): F[Unit] =
          nowSeconds.flatMap { now =>
            tasksRef.update { map =>
              map.get(taskId) match
                case Some(e) if !e.status.isTerminal =>
                  map.updated(taskId, e.copy(status = TaskStatus.InputRequired, inputRequest = Some(inputRequest), lastUpdatedAt = now))
                case _ => map
            }
          }

        def cancel(taskId: String, owner: Option[String]): F[Option[CancelTaskResult]] =
          nowSeconds.flatMap { now =>
            tasksRef.modify { map =>
              map.get(taskId).filter(_.visibleTo(owner)) match
                case Some(e) if !e.status.isTerminal =>
                  val updated = e.copy(status = TaskStatus.Cancelled, lastUpdatedAt = now)
                  (map.updated(taskId, updated), Some((e.cancelHook, CancelTaskResult(taskId, TaskStatus.Cancelled))))
                case Some(e) =>
                  (map, Some((Temporal[F].unit, CancelTaskResult(taskId, e.status))))
                case None =>
                  (map, None)
            }.flatMap {
              case Some((hook, res)) => hook.as(Some(res))
              case None              => Temporal[F].pure(None)
            }
          }

        def update(taskId: String, inputResponses: Map[String, Json], owner: Option[String]): F[Option[UpdateTaskResult]] =
          val _ = inputResponses
          nowSeconds.flatMap { now =>
            tasksRef.modify { map =>
              map.get(taskId).filter(_.visibleTo(owner)) match
                case Some(e) if e.status == TaskStatus.InputRequired =>
                  // Transition back to Working as input was provided
                  val updated = e.copy(status = TaskStatus.Working, inputRequest = None, lastUpdatedAt = now)
                  (map.updated(taskId, updated), Some(UpdateTaskResult(taskId, TaskStatus.Working)))
                case Some(e) =>
                  (map, Some(UpdateTaskResult(taskId, e.status)))
                case None =>
                  (map, None)
            }
          }
    }
