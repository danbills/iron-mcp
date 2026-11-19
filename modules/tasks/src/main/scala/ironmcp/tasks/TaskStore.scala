package ironmcp
package tasks

import cats.effect.{Deferred, Ref, Temporal}
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

  /** Marks a task `input_required` with what it asks, keyed as the answers
    * will come back. Nothing waits for them: see [[awaitInput]] for that.
    */
  def requireInput(taskId: String, inputRequests: Map[String, Json]): F[Unit]

  /** Marks a task `input_required` and waits for the `tasks/update` that
    * answers it, which sets it `working` again. `None` when the task is gone
    * or already over. Cancelling the task cancels the wait.
    */
  def awaitInput(taskId: String, inputRequests: Map[String, Json]): F[Option[Map[String, Json]]]

  /** The task's `statusMessage`, while it is still running. */
  def setStatusMessage(taskId: String, message: String): F[Unit]

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
      inputRequests: Option[Map[String, Json]] = None,
      statusMessage: Option[String] = None,
      waiting: Option[Deferred[F, Map[String, Json]]] = None,
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
        inputRequests = inputRequests,
        statusMessage = statusMessage
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

        def requireInput(taskId: String, inputRequests: Map[String, Json]): F[Unit] =
          asking(taskId, inputRequests, None).void

        def awaitInput(taskId: String, inputRequests: Map[String, Json]): F[Option[Map[String, Json]]] =
          Deferred[F, Map[String, Json]].flatMap { answer =>
            asking(taskId, inputRequests, Some(answer)).flatMap(asked =>
              if asked then answer.get.map(Some(_)) else Temporal[F].pure(None)
            )
          }

        /** Sets the task waiting on `inputRequests`; false when there is no running task to set. */
        private def asking(taskId: String, inputRequests: Map[String, Json], waiting: Option[Deferred[F, Map[String, Json]]]): F[Boolean] =
          nowSeconds.flatMap { now =>
            tasksRef.modify { map =>
              map.get(taskId) match
                case Some(e) if !e.status.isTerminal =>
                  val updated = e.copy(status = TaskStatus.InputRequired, inputRequests = Some(inputRequests), waiting = waiting, lastUpdatedAt = now)
                  (map.updated(taskId, updated), true)
                case _ => (map, false)
            }
          }

        def setStatusMessage(taskId: String, message: String): F[Unit] =
          nowSeconds.flatMap { now =>
            tasksRef.update { map =>
              map.get(taskId) match
                case Some(e) if !e.status.isTerminal =>
                  map.updated(taskId, e.copy(statusMessage = Some(message), lastUpdatedAt = now))
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
          nowSeconds.flatMap { now =>
            tasksRef.modify { map =>
              map.get(taskId).filter(_.visibleTo(owner)) match
                case Some(e) if e.status == TaskStatus.InputRequired =>
                  // Back to working, and the answers go to the task waiting on them.
                  val updated = e.copy(status = TaskStatus.Working, inputRequests = None, waiting = None, lastUpdatedAt = now)
                  val deliver = e.waiting.fold(Temporal[F].unit)(_.complete(inputResponses).void)
                  (map.updated(taskId, updated), (deliver, Some(UpdateTaskResult(taskId, TaskStatus.Working))))
                case Some(e) =>
                  (map, (Temporal[F].unit, Some(UpdateTaskResult(taskId, e.status))))
                case None =>
                  (map, (Temporal[F].unit, None))
            }.flatMap((deliver, result) => deliver.as(result))
          }
    }
