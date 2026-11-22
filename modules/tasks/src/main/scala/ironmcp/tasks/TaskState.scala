package ironmcp
package tasks

import io.circe.{Codec, Json}
import ironmcp.eventlog.{EventJournal, Evolve, JournalFailed}
import ironmcp.protocol.{CallToolParams, CallToolResult, JsonRpcError}
import ironmcp.tasks.TaskModels.*

/** Answers a `tasks/update` brought for the requests keyed `asked`, which the task has not taken yet. */
final case class Answers(asked: Set[String], responses: Map[String, Json]) derives Codec.AsObject

/** A task as the store keeps it. Times are epoch milliseconds.
  *
  * `call` is kept only for a tool that can be resumed after a restart, which
  * is run again with it. `checkpoints` are the results of the task's finished
  * steps ([[TaskContext.step]]). `ttlMs` is how long the task is kept once it
  * finishes, counted from its last change. Every task has one: nothing is kept forever.
  */
final case class TaskRecord(
    taskId: String,
    call: Option[CallToolParams],
    owner: Option[String],
    status: TaskStatus,
    pollIntervalMs: Long,
    ttlMs: Long,
    createdAt: Long,
    lastUpdatedAt: Long,
    result: Option[CallToolResult] = None,
    error: Option[JsonRpcError] = None,
    inputRequests: Option[Map[String, Json]] = None,
    answers: Option[Answers] = None,
    statusMessage: Option[String] = None,
    checkpoints: Map[String, Json] = Map.empty
) derives Codec.AsObject:

  /** An owned task is visible to its owner only. */
  def visibleTo(caller: Option[String]): Boolean = owner.forall(o => caller.contains(o))

  /** Finished, and past its TTL. */
  def expired(now: Long): Boolean = status.isTerminal && now - lastUpdatedAt > ttlMs

  def running: Boolean = !status.isTerminal

  def toGetResult: GetTaskResult =
    GetTaskResult(
      taskId = taskId,
      status = status,
      pollIntervalMs = Some(pollIntervalMs),
      ttlMs = Some(ttlMs),
      createdAt = Some(createdAt / 1000),
      lastUpdatedAt = Some(lastUpdatedAt / 1000),
      result = result,
      error = error,
      inputRequests = inputRequests,
      statusMessage = statusMessage
    )

/** What happened to a task: the store's only writes, and what its journal keeps. Each carries its own time, so
  * replaying them reads no clock.
  */
enum TaskEvent derives Codec.AsObject:
  case Created(
      taskId: String,
      call: Option[CallToolParams],
      owner: Option[String],
      pollIntervalMs: Long,
      ttlMs: Long,
      at: Long
  )
  case StatusMessageSet(taskId: String, message: String, at: Long)
  case InputRequested(taskId: String, requests: Map[String, Json], at: Long)
  case Answered(taskId: String, responses: Map[String, Json], at: Long)
  case AnswersTaken(taskId: String, at: Long)
  case Checkpointed(taskId: String, key: String, value: Json, at: Long)
  case RetentionSet(taskId: String, ttlMs: Long, at: Long)
  case Completed(taskId: String, result: CallToolResult, at: Long)
  case Failed(taskId: String, error: JsonRpcError, at: Long)
  case Cancelled(taskId: String, at: Long)
  case Purged(taskIds: List[String])

/** Every task, as the events so far leave them. Applying an event is pure, and the only way the state changes. */
final case class TaskState(tasks: Map[String, TaskRecord]) derives Codec.AsObject:

  def apply(event: TaskEvent): TaskState = event match
    case TaskEvent.Created(id, call, owner, poll, ttl, at) =>
      TaskState(tasks.updated(id, TaskRecord(id, call, owner, TaskStatus.Working, poll, ttl, at, at)))
    case TaskEvent.StatusMessageSet(id, message, at) =>
      running(id, at)(_.copy(statusMessage = Some(message)))
    case TaskEvent.InputRequested(id, requests, at) =>
      running(id, at)(_.copy(status = TaskStatus.InputRequired, inputRequests = Some(requests), answers = None))
    case TaskEvent.Answered(id, responses, at) =>
      running(id, at) { t =>
        val asked = t.inputRequests.fold(Set.empty[String])(_.keySet)
        t.copy(status = TaskStatus.Working, inputRequests = None, answers = Some(Answers(asked, responses)))
      }
    case TaskEvent.AnswersTaken(id, at) =>
      running(id, at)(_.copy(answers = None))
    case TaskEvent.Checkpointed(id, key, value, at) =>
      running(id, at)(t => t.copy(checkpoints = t.checkpoints.updated(key, value)))
    case TaskEvent.RetentionSet(id, ttl, at) =>
      changed(id, at)(_.copy(ttlMs = ttl))
    case TaskEvent.Completed(id, result, at) =>
      running(id, at)(finished(_).copy(status = TaskStatus.Completed, result = Some(result)))
    case TaskEvent.Failed(id, error, at) =>
      running(id, at)(finished(_).copy(status = TaskStatus.Failed, error = Some(error)))
    case TaskEvent.Cancelled(id, at) =>
      running(id, at)(finished(_).copy(status = TaskStatus.Cancelled))
    case TaskEvent.Purged(ids) =>
      TaskState(tasks -- ids)

  /** Ids of finished tasks past their TTL. */
  def expired(now: Long): List[String] = tasks.valuesIterator.filter(_.expired(now)).map(_.taskId).toList

  def runningTask(id: String): Option[TaskRecord] = tasks.get(id).filter(_.running)

  /** A finished task keeps its result; what it was waiting on and its steps go. */
  private def finished(t: TaskRecord): TaskRecord =
    t.copy(inputRequests = None, answers = None, checkpoints = Map.empty, call = None)

  private def running(id: String, at: Long)(f: TaskRecord => TaskRecord): TaskState =
    runningTask(id).fold(this)(t => TaskState(tasks.updated(id, f(t).copy(lastUpdatedAt = at))))

  private def changed(id: String, at: Long)(f: TaskRecord => TaskRecord): TaskState =
    tasks.get(id).fold(this)(t => TaskState(tasks.updated(id, f(t).copy(lastUpdatedAt = at))))

object TaskState:
  val empty: TaskState = TaskState(Map.empty)

  given Evolve[TaskState, TaskEvent] with
    def initial: TaskState                                  = empty
    def apply(state: TaskState, event: TaskEvent): TaskState = state(event)

/** Where a store's events go to last: [[TaskStore.journaled]] writes each batch here before answering. */
type TaskJournal[F[_]] = EventJournal[F, TaskEvent, TaskState]

/** A journal could not write, so the store stopped taking changes: restart the server to carry on from the journal. */
type TaskJournalFailed = JournalFailed
val TaskJournalFailed: JournalFailed.type = JournalFailed
