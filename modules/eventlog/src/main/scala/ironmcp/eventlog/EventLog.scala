package ironmcp
package eventlog

import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.effect.{Async, Deferred, Ref, Resource, Temporal}
import cats.syntax.all.*

import scala.util.control.NonFatal

/** An event-sourced state that changes one decision at a time.
  *
  * A change is a decision: given the state and the time (epoch milliseconds), the events to apply and the answer
  * to give. Decisions are taken in order, each against the state the ones before it left, so a decision is a
  * compare-and-set with no lost updates. Reads ([[state]]) never wait for writes.
  */
trait EventLog[F[_], E, S]:

  /** The state as of the last change answered. */
  def state: F[S]

  /** Decides one change, applies its events, and gives them with the answer. A decision that throws fails this
    * change only, and changes nothing.
    */
  def submit[A](decide: (S, Long) => (List[E], A)): F[(List[E], A)]

object EventLog:

  /** A log in memory only, starting from `initial`. */
  def inMemory[F[_]: Temporal, E, S](initial: S)(using evolve: Evolve[S, E]): F[EventLog[F, E, S]] =
    Ref.of[F, S](initial).map { ref =>
      new EventLog[F, E, S]:
        def state: F[S] = ref.get
        def submit[A](decide: (S, Long) => (List[E], A)): F[(List[E], A)] =
          Temporal[F].realTime.flatMap { now =>
            ref.modify { s =>
              val (events, a) = decide(s, now.toMillis)
              (Evolve.replay(s, events), (events, a))
            }
          }
    }

  /** A log whose state outlives the process, in the manner of LMAX: one fiber takes changes in order, decides each
    * against the state in memory, writes the batch's events to `journal` (one write and sync for up to `maxBatch`
    * changes), and only then makes the new state visible and answers.
    *
    * It starts from the journal's state. Should the journal fail, the log takes no more changes: that change and
    * every later one fail with [[JournalFailed]].
    */
  def journaled[F[_]: Async, E, S](journal: EventJournal[F, E, S], maxBatch: Int = 1024)(using
      evolve: Evolve[S, E]
  ): Resource[F, EventLog[F, E, S]] =
    for
      ref   <- Resource.eval(Ref.of[F, S](journal.recovered))
      queue <- Resource.eval(Queue.unbounded[F, Op[F, E, S]])
      _     <- processor(journal, ref, queue, math.max(1, maxBatch)).background
    yield new EventLog[F, E, S]:
      def state: F[S] = ref.get
      def submit[A](decide: (S, Long) => (List[E], A)): F[(List[E], A)] =
        Deferred[F, Either[Throwable, (List[E], A)]].flatMap { reply =>
          val op = Op[F, E, S](
            (s, now) =>
              val decided = decide(s, now)
              (decided._1, reply.complete(Right(decided)).void)
            ,
            e => reply.complete(Left(e)).void
          )
          queue.offer(op) *> reply.get.rethrow
        }

  /** A change waiting for the processor: decided, it gives its events and how to answer. */
  private final case class Op[F[_], E, S](decide: (S, Long) => (List[E], F[Unit]), fail: Throwable => F[Unit])

  /** The single writer. A decision that throws fails its own change only; a journal that fails stops all. */
  private def processor[F[_], E, S](journal: EventJournal[F, E, S], ref: Ref[F, S], queue: Queue[F, Op[F, E, S]], maxBatch: Int)(using
      F: Async[F],
      evolve: Evolve[S, E]
  ): F[Unit] =
    def stopped(cause: JournalFailed): F[Unit] =
      queue.take.flatMap(_.fail(cause)).foreverM

    def loop: F[Unit] =
      (queue.take, queue.tryTakeN(Some(maxBatch - 1))).mapN(_ :: _).flatMap { batch =>
        (ref.get, F.realTime).flatMapN { (start, time) =>
          val now = time.toMillis
          val (state, events, replies) =
            batch.foldLeft((start, Vector.empty[E], Vector.empty[F[Unit]])) { case ((s, es, rs), op) =>
              try
                val (out, reply) = op.decide(s, now)
                (Evolve.replay(s, out), es ++ out, rs :+ reply)
              catch case NonFatal(e) => (s, es, rs :+ op.fail(e))
            }
          val written = if events.isEmpty then F.unit else journal.append(events.toList, state)
          written.uncancelable.attempt.flatMap {
            case Right(_) => ref.set(state) *> replies.sequence_ *> loop
            case Left(e) =>
              val failed = JournalFailed(e)
              batch.traverse_(_.fail(failed)) *> stopped(failed)
          }
        }
      }
    loop
