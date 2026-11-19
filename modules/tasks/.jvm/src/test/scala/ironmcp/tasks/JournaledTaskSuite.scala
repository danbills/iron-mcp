package ironmcp
package tasks

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.SecureRandom
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.ClientTransport
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.tasks.TaskModels.*
import munit.CatsEffectSuite

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Tasks in a journaled store outlive the process: events and snapshots on disk, state in memory. */
class JournaledTaskSuite extends CatsEffectSuite:

  final case class Order(item: String :| Not[Empty]) derives Decoder, JsonSchemaOf
  final case class Confirm(confirm: Boolean) derives Decoder, JsonSchemaOf

  private def tempDir = IO.blocking(Files.createTempDirectory("iron-mcp-journal"))

  private def store(dir: Path, snapshotEvery: Int = 4096): Resource[IO, TaskStore[IO]] =
    FileTaskJournal.open[IO](dir, snapshotEvery).flatMap(TaskStore.journaled[IO](_, defaultTtlMs = 1.hour.toMillis))

  /** Opened and never closed: what a crash leaves behind (no final snapshot). */
  private def crashing(dir: Path, snapshotEvery: Int = 4096): IO[TaskStore[IO]] =
    store(dir, snapshotEvery).allocated.map(_._1)

  private def files(dir: Path): List[String] =
    val s = Files.list(dir)
    try s.iterator.asScala.map(_.getFileName.toString).toList.sorted
    finally s.close()

  private def text(r: CallToolResult) = r.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString

  test("tasks, their status and results survive a clean restart") {
    for
      dir <- tempDir
      _ <- store(dir).use { s =>
             s.create("t-done", 10L, 60000L, IO.unit, None) *> s.complete("t-done", CallToolResult.text("ok")) *>
               s.create("t-run", 10L, 60000L, IO.unit, Some("alice")) *> s.setStatusMessage("t-run", "halfway")
           }
      (done, run, hidden, open) <- store(dir).use(s =>
                                     (s.get("t-done", None), s.get("t-run", Some("alice")), s.get("t-run", None), s.unfinished).tupled
                                   )
    yield
      assertEquals(done.map(_.status), Some(TaskStatus.Completed))
      assertEquals(done.flatMap(_.result).map(text), Some("ok"))
      assertEquals(run.flatMap(_.statusMessage), Some("halfway"))
      assertEquals(hidden, None)
      assertEquals(open.map(_.taskId), List("t-run"))
  }

  test("and a crash: every answered change is on disk without a snapshot") {
    for
      dir   <- tempDir
      s     <- crashing(dir)
      _     <- s.create("t", 10L, 60000L, IO.unit, None) *> s.setStatusMessage("t", "step 2")
      seen  <- store(dir).use(_.get("t", None))
    yield
      assertEquals(seen.flatMap(_.statusMessage), Some("step 2"))
      assert(!files(dir).exists(_.startsWith("snapshot")), files(dir))
  }

  test("snapshots compact the journal, and replay from them gives the same state") {
    for
      dir    <- tempDir
      before <- store(dir, snapshotEvery = 3).use { s =>
                  (1 to 5).toList.traverse_(i => s.create(s"t$i", 10L, 60000L, IO.unit, None) *> s.setStatusMessage(s"t$i", s"at $i")) *>
                    (1 to 5).toList.traverse(i => s.get(s"t$i", None))
                }
      left   = files(dir)
      after  <- store(dir).use(s => (1 to 5).toList.traverse(i => s.get(s"t$i", None)))
    yield
      assertEquals(after, before)
      assertEquals(left.count(_.startsWith("snapshot")), 1, left)
      assert(left.count(_.startsWith("events")) <= 1, left)
  }

  test("a last line cut short by a crash is dropped; damage anywhere else fails the open") {
    for
      dir     <- tempDir
      s       <- crashing(dir)
      _       <- s.create("t", 10L, 60000L, IO.unit, None) *> s.setStatusMessage("t", "kept")
      segment  = dir.resolve(files(dir).filter(_.startsWith("events")).last)
      _       <- IO.blocking(Files.writeString(segment, """{"seq":3,"ev""", StandardOpenOption.APPEND))
      kept    <- store(dir).use(_.get("t", None))
      _       <- IO.blocking(Files.writeString(segment, "garbage\n" + Files.readString(segment)))
      broken  <- store(dir).use(_ => IO.unit).attempt
    yield
      assertEquals(kept.flatMap(_.statusMessage), Some("kept"))
      assert(broken.left.exists(_.getMessage.contains("unreadable")), broken)
  }

  test("the journal is readable by its owner only") {
    for
      dir   <- tempDir
      sub    = dir.resolve("tasks")
      _     <- store(sub).use(_.create("t", 10L, 60000L, IO.unit, None).void)
      perms <- IO.blocking(files(sub).map(f => PosixFilePermissions.toString(Files.getPosixFilePermissions(sub.resolve(f)))))
      dperm <- IO.blocking(PosixFilePermissions.toString(Files.getPosixFilePermissions(sub)))
    yield
      assert(perms.nonEmpty && perms.forall(_ == "rw-------"), perms)
      assertEquals(dperm, "rwx------")
  }

  test("retention: a finished task goes after its ttl and stays gone; one retained longer stays") {
    for
      dir <- tempDir
      _ <- store(dir).use { s =>
             s.create("short", 10L, 1L, IO.unit, None) *> s.complete("short", CallToolResult.text("x")) *>
               s.create("kept", 10L, 1L, IO.unit, None) *> s.setRetention("kept", 1.day.toMillis) *>
               s.complete("kept", CallToolResult.text("y")) *> IO.sleep(20.millis) *>
               s.create("next", 10L, 60000L, IO.unit, None).void
           }
      (short, kept) <- store(dir).use(s => (s.get("short", None), s.get("kept", None)).tupled)
    yield
      assertEquals(short, None)
      assertEquals(kept.map(_.status), Some(TaskStatus.Completed))
      assertEquals(kept.flatMap(_.ttlMs), Some(1.day.toMillis))
  }

  test("retention is required: no store without a default, and no task kept forever") {
    for
      dir     <- tempDir
      noStore <- FileTaskJournal.open[IO](dir).flatMap(TaskStore.journaled[IO](_, defaultTtlMs = 0L)).use_.attempt
      refused <- store(dir).use(s => s.create("t", 10L, 60000L, IO.unit, None) *> s.setRetention("t", 0L).attempt)
    yield
      assert(noStore.left.exists(_.isInstanceOf[IllegalArgumentException]), noStore)
      assert(refused.left.exists(_.isInstanceOf[IllegalArgumentException]), refused)
  }

  test("a journal that fails stops the store taking changes") {
    val failing = new TaskJournal[IO]:
      val recovered: TaskState = TaskState.empty
      def append(events: List[TaskEvent], after: TaskState): IO[Unit] = IO.raiseError(java.io.IOException("disk full"))
    TaskStore.journaled[IO](failing, defaultTtlMs = 1.hour.toMillis).use { s =>
      for
        first <- s.create("t", 10L, 60000L, IO.unit, None).attempt
        later <- s.setStatusMessage("t", "x").attempt
        seen  <- s.get("t", None)
      yield
        assert(first.left.exists(_.isInstanceOf[TaskJournalFailed]), first)
        assert(later.left.exists(_.isInstanceOf[TaskJournalFailed]), later)
        assertEquals(seen, None)
    }
  }

  /** `checkout` reserves (a step, counted), asks to confirm, then buys (a step). */
  private def server(s: TaskStore[IO], reserved: Ref[IO, Int], resumable: Boolean = true) =
    SecureRandom.javaSecuritySecureRandom[IO].map { random =>
      val checkout = TaskTool[IO, Order]("checkout", "Reserves, asks, buys.", resumable = resumable) { (order, task) =>
        val item: String = order.item
        for
          _      <- task.retain(1.day)
          _      <- task.step("reserve")(reserved.update(_ + 1).as(s"reserved $item"))
          answer <- task.ask(Map("confirm" -> Elicit.form[Confirm](s"Buy $item?")))
          yes     = answer.get("confirm").flatMap(_.as[ElicitResult].toOption)
                      .exists(_.content.flatMap(_("confirm")).flatMap(_.asBoolean).contains(true))
          done   <- task.step("buy")(IO.pure(if yes then s"bought $item" else s"released $item"))
        yield done
      }
      val base = McpServer[IO](Implementation("shop", "0"), tools = Some(ToolSet.of(checkout.mcpTool)))
      TaskServer[IO](base, s, random, defaultPollIntervalMs = 5L, inputTools = List(checkout))
    }

  private val yes = Map("confirm" -> ElicitResult(ElicitAction.Accept, Some(JsonObject("confirm" -> true.asJson))).asJson)

  private def started(server: TaskServer[IO]) =
    for
      client  <- TasksClient[IO](ClientTransport.inProcess(server))
      created <- client.callToolAsTask("checkout", JsonObject("item" -> "lamp".asJson))
      id = created match
             case c: CreateTaskResult => c.taskId
             case other               => fail(s"expected a task, got $other")
      _ <- client.getTask(id).iterateUntil(_.status == TaskStatus.InputRequired).timeout(2.seconds)
    yield id

  test("a crash while a task asks: answered during the downtime, it resumes after its last step and finishes") {
    for
      dir      <- tempDir
      reserved <- Ref[IO].of(0)
      first    <- crashing(dir).flatMap(server(_, reserved))
      id       <- started(first)
      (done, count) <- store(dir).use { s =>
                         for
                           second  <- server(s, reserved)
                           client  <- TasksClient[IO](ClientTransport.inProcess(second))
                           waiting <- client.getTask(id)
                           _       <- client.updateTask(id, yes)
                           _       <- second.recover
                           done    <- client.getTask(id).iterateUntil(_.status.isTerminal).timeout(2.seconds)
                           count   <- reserved.get
                         yield
                           assertEquals(waiting.status, TaskStatus.InputRequired)
                           (done, count)
                       }
    yield
      assertEquals(done.status, TaskStatus.Completed)
      assertEquals(done.result.map(text), Some("bought lamp"))
      assertEquals(done.ttlMs, Some(1.day.toMillis))
      assertEquals(count, 1)
  }

  test("a crash while a task asks: resumed, it asks again, and the answer then finishes it") {
    for
      dir      <- tempDir
      reserved <- Ref[IO].of(0)
      id       <- crashing(dir).flatMap(server(_, reserved)).flatMap(started)
      done <- store(dir).use { s =>
                for
                  second <- server(s, reserved)
                  _      <- second.recover
                  client <- TasksClient[IO](ClientTransport.inProcess(second))
                  asked  <- client.getTask(id).iterateUntil(_.status == TaskStatus.InputRequired).timeout(2.seconds)
                  _      <- client.updateTask(id, yes)
                  done   <- client.getTask(id).iterateUntil(_.status.isTerminal).timeout(2.seconds)
                yield
                  assertEquals(asked.inputRequests.map(_.keySet), Some(Set("confirm")))
                  done
              }
      count <- reserved.get
    yield
      assertEquals(done.result.map(text), Some("bought lamp"))
      assertEquals(count, 1)
  }

  test("a task whose tool cannot resume fails on recovery, saying why") {
    for
      dir      <- tempDir
      reserved <- Ref[IO].of(0)
      id       <- crashing(dir).flatMap(server(_, reserved, resumable = false)).flatMap(started)
      done <- store(dir).use { s =>
                server(s, reserved, resumable = false).flatMap(second => second.recover *> s.get(id, None))
              }
    yield
      assertEquals(done.map(_.status), Some(TaskStatus.Failed))
      assertEquals(done.flatMap(_.error).map(_.message: String), Some("the server restarted while this task ran"))
  }
