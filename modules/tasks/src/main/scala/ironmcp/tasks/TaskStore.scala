package ironmcp
package tasks

import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.effect.{Async, Concurrent, Deferred, Ref, Resource, Temporal}
import cats.syntax.all.*
import io.circe.Json
import ironmcp.protocol.{CallToolParams, CallToolResult, JsonRpcError}
import ironmcp.tasks.TaskModels.*

import scala.util.control.NonFatal

/** Storage and state machine for background MCP tasks.
  *
  * A task may have an owner: the caller that started it (over HTTP with
  * authorization, its token's subject). `get`, `cancel` and `update` are
  * answered only for that same owner; for anyone else the task does not
  * exist, so a task ID alone is not enough to read or cancel someone else's
  * work. A task created without an owner is reachable by anyone holding its
  * ID.
  *
  * The store is event-sourced: every change is a [[TaskEvent]] applied to an
  * immutable [[TaskState]]. [[TaskStore.inMemory]] keeps the state only;
  * [[TaskStore.journaled]] also writes the events to a [[TaskJournal]], so the
  * tasks outlive the process.
  */
trait TaskStore[F[_]]:
  def get(taskId: String, owner: Option[String]): F[Option[GetTaskResult]]
  def cancel(taskId: String, owner: Option[String]): F[Option[CancelTaskResult]]
  def update(taskId: String, inputResponses: Map[String, Json], owner: Option[String]): F[Option[UpdateTaskResult]]

  /** A new task, `working`. `call` is kept for a task that can be resumed after a restart ([[unfinished]]). */
  def create(
      taskId: String,
      pollIntervalMs: Long,
      ttlMs: Long,
      cancelHook: F[Unit],
      owner: Option[String],
      call: Option[CallToolParams] = None
  ): F[CreateTaskResult]

  /** What cancels the fiber now running `taskId`: for a task resumed after a restart. */
  def attach(taskId: String, cancelHook: F[Unit]): F[Unit]

  def complete(taskId: String, result: CallToolResult): F[Unit]
  def fail(taskId: String, error: JsonRpcError): F[Unit]

  /** Marks a task `input_required` with what it asks, keyed as the answers
    * will come back. Nothing waits for them: see [[awaitInput]] for that.
    */
  def requireInput(taskId: String, inputRequests: Map[String, Json]): F[Unit]

  /** Marks a task `input_required` and waits for the `tasks/update` that
    * answers it, which sets it `working` again. `None` when the task is gone
    * or already over. Cancelling the task cancels the wait.
    *
    * Answers that came for these same keys while nothing waited (the server
    * restarted while the task asked) are taken at once, without asking again.
    */
  def awaitInput(taskId: String, inputRequests: Map[String, Json]): F[Option[Map[String, Json]]]

  /** The task's `statusMessage`, while it is still running. */
  def setStatusMessage(taskId: String, message: String): F[Unit]

  /** How long the task is kept once it finishes (its `ttlMs`). A non-positive one is refused: every task goes eventually. */
  def setRetention(taskId: String, ttlMs: Long): F[Unit]

  /** The recorded result of the running task's step `key`, if it finished. */
  def checkpoint(taskId: String, key: String): F[Option[Json]]

  /** Records the running task's step `key` as finished with `value`. */
  def recordCheckpoint(taskId: String, key: String, value: Json): F[Unit]

  /** Tasks still `working` or `input_required`: after a restart, ones no fiber runs. */
  def unfinished: F[List[UnfinishedTask]]

/** A task left running, with the call that started it when it can be resumed. */
final case class UnfinishedTask(taskId: String, call: Option[CallToolParams], owner: Option[String])

object TaskStore:

  /** An in-memory task store suitable for single-node servers and testing. */
  def inMemory[F[_]: Temporal](
      defaultPollIntervalMs: Long = 1000L,
      defaultTtlMs: Long = 300000L
  ): F[TaskStore[F]] =
    Ref.of[F, TaskState](TaskState.empty).flatMap { ref =>
      val sequencer = new Sequencer[F]:
        def state: F[TaskState] = ref.get
        def submit[A](decide: Decide[A]): F[(List[TaskEvent], A)] =
          Temporal[F].realTime.flatMap { now =>
            ref.modify { s =>
              val (events, a) = decide(s, now.toMillis)
              (events.foldLeft(s)(_.apply(_)), (events, a))
            }
          }
      Ref.of[F, Map[String, Live[F]]](Map.empty).map(Store(sequencer, _, defaultPollIntervalMs, defaultTtlMs))
    }

  /** A store whose tasks outlive the process, in the manner of LMAX: one fiber takes changes in order, decides each
    * against the state in memory, writes the batch's events to `journal` (one write and sync for up to `maxBatch`
    * changes), and only then makes the new state visible and answers. Reads go to the state in memory.
    *
    * It starts from the journal's state. Tasks it holds unfinished have no fiber: [[TaskServer.recover]] runs
    * them again or fails them. Should the journal fail, the store takes no more changes ([[TaskJournalFailed]]).
    *
    * `defaultTtlMs` has no default: how long finished tasks stay on disk is a decision to make, not to inherit.
    * It applies to a task created with no TTL of its own; [[TaskContext.retain]] changes a task's.
    */
  def journaled[F[_]: Async](
      journal: TaskJournal[F],
      defaultTtlMs: Long,
      defaultPollIntervalMs: Long = 1000L,
      maxBatch: Int = 1024
  ): Resource[F, TaskStore[F]] =
    for
      _     <- Resource.eval(Async[F].raiseError[Unit](IllegalArgumentException(s"defaultTtlMs must be positive, was $defaultTtlMs")).whenA(defaultTtlMs <= 0))
      ref   <- Resource.eval(Ref.of[F, TaskState](journal.recovered))
      queue <- Resource.eval(Queue.unbounded[F, Op[F]])
      _     <- processor(journal, ref, queue, math.max(1, maxBatch)).background
      live  <- Resource.eval(Ref.of[F, Map[String, Live[F]]](Map.empty))
    yield
      val sequencer = new Sequencer[F]:
        def state: F[TaskState] = ref.get
        def submit[A](decide: Decide[A]): F[(List[TaskEvent], A)] =
          Deferred[F, Either[Throwable, (List[TaskEvent], A)]].flatMap { reply =>
            val op = Op[F](
              (s, now) =>
                val decided = decide(s, now)
                (decided._1, reply.complete(Right(decided)).void)
              ,
              e => reply.complete(Left(e)).void
            )
            queue.offer(op) *> reply.get.rethrow
          }
      Store(sequencer, live, defaultPollIntervalMs, defaultTtlMs)

  /** A change: its events and its answer, decided against the state and the time. */
  private type Decide[A] = (TaskState, Long) => (List[TaskEvent], A)

  /** Applies decisions one at a time, in order. */
  private trait Sequencer[F[_]]:
    def state: F[TaskState]
    def submit[A](decide: Decide[A]): F[(List[TaskEvent], A)]

  /** A change waiting for the processor: decided, it gives its events and how to answer. */
  private final case class Op[F[_]](decide: (TaskState, Long) => (List[TaskEvent], F[Unit]), fail: Throwable => F[Unit])

  /** The single writer. A decision that throws fails its own change only; a journal that fails stops all. */
  private def processor[F[_]](journal: TaskJournal[F], ref: Ref[F, TaskState], queue: Queue[F, Op[F]], maxBatch: Int)(using
      F: Async[F]
  ): F[Unit] =
    def stopped(cause: TaskJournalFailed): F[Unit] =
      queue.take.flatMap(_.fail(cause)).foreverM

    def loop: F[Unit] =
      (queue.take, queue.tryTakeN(Some(maxBatch - 1))).mapN(_ :: _).flatMap { batch =>
        (ref.get, F.realTime).flatMapN { (start, time) =>
          val now = time.toMillis
          val (state, events, replies) =
            batch.foldLeft((start, Vector.empty[TaskEvent], Vector.empty[F[Unit]])) { case ((s, es, rs), op) =>
              try
                val (out, reply) = op.decide(s, now)
                (out.foldLeft(s)(_.apply(_)), es ++ out, rs :+ reply)
              catch case NonFatal(e) => (s, es, rs :+ op.fail(e))
            }
          val written = if events.isEmpty then F.unit else journal.append(events.toList, state)
          written.uncancelable.attempt.flatMap {
            case Right(_) => ref.set(state) *> replies.sequence_ *> loop
            case Left(e) =>
              val failed = TaskJournalFailed(e)
              batch.traverse_(_.fail(failed)) *> stopped(failed)
          }
        }
      }
    loop

  /** What lives only in this process: the fiber's cancel hook, and the wait of a task asking for input. */
  private final case class Live[F[_]](cancelHook: F[Unit], waiter: Option[Deferred[F, Unit]])

  private enum Asked:
    case Given(responses: Map[String, Json])
    case Waiting
    case Over

  private final class Store[F[_]](
      sequencer: Sequencer[F],
      live: Ref[F, Map[String, Live[F]]],
      defaultPollIntervalMs: Long,
      defaultTtlMs: Long
  )(using F: Concurrent[F])
      extends TaskStore[F]:

    /** Decides a change, then does what its events ask of this process. */
    private def change[A](decide: Decide[A]): F[A] =
      sequencer.submit(decide).flatMap((events, a) => events.traverse_(react).as(a))

    private def react(event: TaskEvent): F[Unit] = event match
      case TaskEvent.Answered(id, _, _) =>
        live.get.flatMap(_.get(id).flatMap(_.waiter).traverse_(_.complete(()).void))
      case TaskEvent.Completed(id, _, _) => live.update(_ - id)
      case TaskEvent.Failed(id, _, _)    => live.update(_ - id)
      case TaskEvent.Purged(ids)         => live.update(_ -- ids)
      case _                             => F.unit

    def get(taskId: String, owner: Option[String]): F[Option[GetTaskResult]] =
      sequencer.state.map(_.tasks.get(taskId).filter(_.visibleTo(owner)).map(_.toGetResult))

    def create(
        taskId: String,
        pollIntervalMs: Long,
        ttlMs: Long,
        cancelHook: F[Unit],
        owner: Option[String],
        call: Option[CallToolParams]
    ): F[CreateTaskResult] =
      val poll = if pollIntervalMs > 0 then pollIntervalMs else defaultPollIntervalMs
      val ttl  = if ttlMs > 0 then ttlMs else defaultTtlMs
      live.update(_.updated(taskId, Live(cancelHook, None))) *>
        change { (s, now) =>
          // Finished tasks past their TTL go, so the state cannot grow without bound.
          val expired = s.expired(now)
          val purge   = if expired.isEmpty then Nil else List(TaskEvent.Purged(expired))
          (purge :+ TaskEvent.Created(taskId, call, owner, poll, ttl, now), ())
        }.as(CreateTaskResult(taskId = taskId, status = TaskStatus.Working, pollIntervalMs = Some(poll), ttlMs = Some(ttl)))

    def attach(taskId: String, cancelHook: F[Unit]): F[Unit] =
      live.update(m => m.updated(taskId, m.get(taskId).fold(Live(cancelHook, None))(_.copy(cancelHook = cancelHook))))

    def complete(taskId: String, result: CallToolResult): F[Unit] =
      whileRunning(taskId)(TaskEvent.Completed(taskId, result, _))

    def fail(taskId: String, error: JsonRpcError): F[Unit] =
      whileRunning(taskId)(TaskEvent.Failed(taskId, error, _))

    def requireInput(taskId: String, inputRequests: Map[String, Json]): F[Unit] =
      whileRunning(taskId)(TaskEvent.InputRequested(taskId, inputRequests, _))

    def setStatusMessage(taskId: String, message: String): F[Unit] =
      whileRunning(taskId)(TaskEvent.StatusMessageSet(taskId, message, _))

    def recordCheckpoint(taskId: String, key: String, value: Json): F[Unit] =
      whileRunning(taskId)(TaskEvent.Checkpointed(taskId, key, value, _))

    def setRetention(taskId: String, ttlMs: Long): F[Unit] =
      F.raiseError[Unit](IllegalArgumentException(s"retention must be positive, was $ttlMs ms")).whenA(ttlMs <= 0) *>
      change((s, now) => (s.tasks.get(taskId).map(_ => TaskEvent.RetentionSet(taskId, ttlMs, now)).toList, ()))

    def checkpoint(taskId: String, key: String): F[Option[Json]] =
      sequencer.state.map(_.runningTask(taskId).flatMap(_.checkpoints.get(key)))

    def unfinished: F[List[UnfinishedTask]] =
      sequencer.state.map(_.tasks.valuesIterator.filter(_.running).map(t => UnfinishedTask(t.taskId, t.call, t.owner)).toList)

    private def whileRunning(taskId: String)(event: Long => TaskEvent): F[Unit] =
      change((s, now) => (s.runningTask(taskId).map(_ => event(now)).toList, ()))

    def awaitInput(taskId: String, inputRequests: Map[String, Json]): F[Option[Map[String, Json]]] =
      def ask(s: TaskState, now: Long): (List[TaskEvent], Asked) = s.runningTask(taskId) match
        case None => (Nil, Asked.Over)
        case Some(t) =>
          t.answers match
            case Some(a) if a.asked == inputRequests.keySet => (List(TaskEvent.AnswersTaken(taskId, now)), Asked.Given(a.responses))
            case _ if t.status == TaskStatus.InputRequired && t.inputRequests.contains(inputRequests) => (Nil, Asked.Waiting)
            case _ => (List(TaskEvent.InputRequested(taskId, inputRequests, now)), Asked.Waiting)

      val clear = live.update(_.updatedWith(taskId)(_.map(_.copy(waiter = None))))

      // The waiter is in place before asking, so an answer cannot slip in between.
      def round: F[Option[Map[String, Json]]] =
        Deferred[F, Unit].flatMap { signal =>
          live.update(m => m.updated(taskId, m.get(taskId).fold(Live(F.unit, Some(signal)))(_.copy(waiter = Some(signal))))) *>
            change(ask).flatMap {
              case Asked.Given(responses) => clear.as(Some(responses))
              case Asked.Over             => clear.as(None)
              case Asked.Waiting          => signal.get *> round
            }
        }
      round

    def cancel(taskId: String, owner: Option[String]): F[Option[CancelTaskResult]] =
      change { (s, now) =>
        s.tasks.get(taskId).filter(_.visibleTo(owner)) match
          case Some(t) if t.running => (List(TaskEvent.Cancelled(taskId, now)), Some(CancelTaskResult(taskId, TaskStatus.Cancelled)))
          case Some(t)              => (Nil, Some(CancelTaskResult(taskId, t.status)))
          case None                 => (Nil, None)
      }.flatTap {
        case Some(CancelTaskResult(_, TaskStatus.Cancelled)) =>
          live.modify(m => (m - taskId, m.get(taskId))).flatMap(_.traverse_(_.cancelHook))
        case _ => F.unit
      }

    def update(taskId: String, inputResponses: Map[String, Json], owner: Option[String]): F[Option[UpdateTaskResult]] =
      change { (s, now) =>
        s.tasks.get(taskId).filter(_.visibleTo(owner)) match
          case Some(t) if t.status == TaskStatus.InputRequired =>
            // Back to working; the answers wait in the task until it takes them.
            (List(TaskEvent.Answered(taskId, inputResponses, now)), Some(UpdateTaskResult(taskId, TaskStatus.Working)))
          case Some(t) => (Nil, Some(UpdateTaskResult(taskId, t.status)))
          case None    => (Nil, None)
      }
