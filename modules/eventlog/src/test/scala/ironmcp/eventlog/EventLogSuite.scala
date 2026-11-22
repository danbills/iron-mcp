package ironmcp
package eventlog

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

/** A counter as events: the smallest state worth sourcing. */
enum Counted:
  case Added(n: Int)
  case Reset

object Counted:
  given Evolve[Int, Counted] with
    def initial: Int = 0
    def apply(state: Int, event: Counted): Int = event match
      case Added(n) => state + n
      case Reset    => 0

class EventLogSuite extends CatsEffectSuite:

  private def add(n: Int): (Int, Long) => (List[Counted], Int) = (s, _) => (List(Counted.Added(n)), s + n)

  /** A journal in memory that records what it was asked to write, and can be told to fail. */
  private def recording(failing: Boolean = false) =
    Ref.of[IO, Vector[(List[Counted], Int)]](Vector.empty).map { written =>
      val journal = new EventJournal[IO, Counted, Int]:
        val recovered: Int = 0
        def append(events: List[Counted], after: Int): IO[Unit] =
          if failing then IO.raiseError(java.io.IOException("disk full")) else written.update(_ :+ (events, after))
      (journal, written)
    }

  test("in memory: decisions apply in order and answer with their events") {
    for
      log            <- EventLog.inMemory[IO, Counted, Int](10)
      (events, seen) <- log.submit(add(5))
      _              <- log.submit((_, _) => (List(Counted.Reset, Counted.Added(2)), ()))
      state          <- log.state
    yield
      assertEquals(events, List(Counted.Added(5)))
      assertEquals(seen, 15)
      assertEquals(state, 2)
  }

  test("journaled: concurrent changes lose no update, and the journal sees every event with the state after") {
    recording().flatMap { (journal, written) =>
      EventLog.journaled[IO, Counted, Int](journal, maxBatch = 8).use { log =>
        for
          _     <- (1 to 100).toList.parTraverse_(_ => log.submit(add(1)))
          state <- log.state
          out   <- written.get
        yield
          assertEquals(state, 100)
          assertEquals(out.flatMap(_._1).size, 100)
          assertEquals(out.last._2, 100)
          assert(out.forall(_._1.size <= 8), out.map(_._1.size))
      }
    }
  }

  test("journaled: starts from what the journal recovered") {
    val journal = new EventJournal[IO, Counted, Int]:
      val recovered: Int                                     = 41
      def append(events: List[Counted], after: Int): IO[Unit] = IO.unit
    EventLog.journaled[IO, Counted, Int](journal).use(log => log.submit(add(1)) *> log.state).assertEquals(42)
  }

  test("a decision that throws fails its own change only; one with no events writes nothing") {
    recording().flatMap { (journal, written) =>
      EventLog.journaled[IO, Counted, Int](journal).use { log =>
        for
          bad   <- log.submit[Unit]((_, _) => throw IllegalStateException("no")).attempt
          none  <- log.submit((s, _) => (Nil, s))
          _     <- log.submit(add(3))
          state <- log.state
          out   <- written.get
        yield
          assert(bad.left.exists(_.getMessage == "no"), bad)
          assertEquals(none._2, 0)
          assertEquals(state, 3)
          assertEquals(out.map(_._1), Vector(List(Counted.Added(3))))
      }
    }
  }

  test("a journal that fails stops the log: that change and every later one fail, and the state stays") {
    recording(failing = true).flatMap { (journal, _) =>
      EventLog.journaled[IO, Counted, Int](journal).use { log =>
        for
          first <- log.submit(add(1)).attempt
          later <- log.submit((s, _) => (Nil, s)).attempt
          state <- log.state
        yield
          assert(first.left.exists(_.isInstanceOf[JournalFailed]), first)
          assert(later.left.exists(_.isInstanceOf[JournalFailed]), later)
          assertEquals(state, 0)
      }
    }
  }

  test("replay folds events from a state") {
    assertEquals(Evolve.replay(1, List(Counted.Added(2), Counted.Reset, Counted.Added(4))), 4)
    assertEquals(Evolve[Int, Counted].initial, 0)
  }
