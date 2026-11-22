package ironmcp
package eventlog

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Codec
import munit.CatsEffectSuite

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** A list of notes, kept on disk: events and snapshots, state in memory. */
final case class Noted(text: String) derives Codec.AsObject

object Noted:
  given Evolve[List[String], Noted] with
    def initial: List[String]                                 = Nil
    def apply(state: List[String], event: Noted): List[String] = state :+ event.text

class FileEventJournalSuite extends CatsEffectSuite:

  private def tempDir = IO.blocking(Files.createTempDirectory("iron-mcp-eventlog"))

  private def log(dir: Path, snapshotEvery: Int = 4096): Resource[IO, EventLog[IO, Noted, List[String]]] =
    FileEventJournal.open[IO, Noted, List[String]](dir, snapshotEvery).flatMap(EventLog.journaled[IO, Noted, List[String]](_))

  private def note(text: String): (List[String], Long) => (List[Noted], Unit) = (_, _) => (List(Noted(text)), ())

  private def files(dir: Path): List[String] =
    val s = Files.list(dir)
    try s.iterator.asScala.map(_.getFileName.toString).toList.sorted
    finally s.close()

  test("the state survives a clean restart and a crash (no final snapshot)") {
    for
      dir     <- tempDir
      _       <- log(dir).use(l => l.submit(note("a")) *> l.submit(note("b")))
      crashed <- log(dir).allocated.map(_._1)
      _       <- crashed.submit(note("c"))
      state   <- log(dir).use(_.state)
    yield assertEquals(state, List("a", "b", "c"))
  }

  test("snapshots cover the events before them, and the files they cover go") {
    for
      dir   <- tempDir
      _     <- log(dir, snapshotEvery = 2).use(l => List("a", "b", "c", "d", "e").traverse_(t => l.submit(note(t))))
      names  = files(dir)
      state <- log(dir).use(_.state)
    yield
      assertEquals(state, List("a", "b", "c", "d", "e"))
      assertEquals(names.count(_.startsWith("snapshot")), 1, names)
      assert(names.forall(!_.endsWith(".tmp")), names)
  }

  test("a last line cut short by a crash is dropped; damage anywhere else fails the open") {
    for
      dir     <- tempDir
      crashed <- log(dir).allocated.map(_._1)
      _       <- crashed.submit(note("kept"))
      segment  = dir.resolve(files(dir).filter(_.startsWith("events")).last)
      _       <- IO.blocking(Files.writeString(segment, """{"seq":2,"ev""", StandardOpenOption.APPEND))
      kept    <- log(dir).use(_.state)
      _       <- IO.blocking(Files.writeString(segment, "garbage\n" + Files.readString(segment)))
      broken  <- log(dir).use_.attempt
    yield
      assertEquals(kept, List("kept"))
      assert(broken.left.exists(_.getMessage.contains("unreadable")), broken)
  }

  test("an event out of sequence fails the open") {
    for
      dir    <- tempDir
      _      <- IO.blocking(Files.writeString(dir.resolve("events-00000000000000000001.jsonl"), """{"seq":5,"event":{"text":"x"}}""" + "\n"))
      broken <- log(dir).use_.attempt
    yield assert(broken.left.exists(_.getMessage.contains("event 5 follows 0")), broken)
  }

  test("the journal is readable by its owner only") {
    for
      dir   <- tempDir
      sub    = dir.resolve("log")
      _     <- log(sub).use(_.submit(note("secret")))
      perms <- IO.blocking(files(sub).map(f => PosixFilePermissions.toString(Files.getPosixFilePermissions(sub.resolve(f)))))
      dperm <- IO.blocking(PosixFilePermissions.toString(Files.getPosixFilePermissions(sub)))
    yield
      assert(perms.nonEmpty && perms.forall(_ == "rw-------"), perms)
      assertEquals(dperm, "rwx------")
  }
