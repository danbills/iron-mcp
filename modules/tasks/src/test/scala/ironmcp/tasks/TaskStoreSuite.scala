package ironmcp
package tasks

import cats.effect.{IO, Ref}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.{CallToolResult, ContentBlock, JsonRpcError}
import ironmcp.tasks.TaskModels.*
import munit.CatsEffectSuite

class TaskStoreSuite extends CatsEffectSuite:

  test("TaskStore lifecycle: working -> completed"):
    for
      store <- TaskStore.inMemory[IO](defaultPollIntervalMs = 500L)
      created <- store.create("task-1", pollIntervalMs = 500L, ttlMs = 60000L, cancelHook = IO.unit, owner = None)
      _ = assertEquals(created.taskId, "task-1")
      _ = assertEquals(created.status, TaskStatus.Working)

      initialSnap <- store.get("task-1", None)
      _ = assertEquals(initialSnap.map(_.status), Some(TaskStatus.Working))

      callRes = CallToolResult(content = List(ContentBlock.Text("Done!")))
      _ <- store.complete("task-1", callRes)

      completedSnap <- store.get("task-1", None)
      _ = assertEquals(completedSnap.map(_.status), Some(TaskStatus.Completed))
      _ = assertEquals(completedSnap.flatMap(_.result), Some(callRes))
    yield ()

  test("TaskStore cancellation triggers cancelHook and sets status to Cancelled"):
    for
      cancelledHookRan <- Ref.of[IO, Boolean](false)
      store <- TaskStore.inMemory[IO]()
      _ <- store.create("task-cancel", pollIntervalMs = 500L, ttlMs = 60000L, cancelHook = cancelledHookRan.set(true), owner = None)

      cancelRes <- store.cancel("task-cancel", None)
      _ = assertEquals(cancelRes.map(_.status), Some(TaskStatus.Cancelled))

      hookTriggered <- cancelledHookRan.get
      _ = assert(hookTriggered)

      snap <- store.get("task-cancel", None)
      _ = assertEquals(snap.map(_.status), Some(TaskStatus.Cancelled))
    yield ()

  test("Terminal state is immutable"):
    for
      store <- TaskStore.inMemory[IO]()
      _ <- store.create("task-terminal", pollIntervalMs = 500L, ttlMs = 60000L, cancelHook = IO.unit, owner = None)
      _ <- store.complete("task-terminal", CallToolResult(content = Nil))

      // Attempting to fail or cancel a completed task does not alter its terminal status
      _ <- store.fail("task-terminal", JsonRpcError(ironmcp.protocol.ErrorCode.InternalError, "too late".assume[Not[Empty]]))
      snap <- store.get("task-terminal", None)
      _ = assertEquals(snap.map(_.status), Some(TaskStatus.Completed))
    yield ()

  test("An owned task is invisible to everyone but its owner"):
    for
      store   <- TaskStore.inMemory[IO]()
      _       <- store.create("task-alice", pollIntervalMs = 500L, ttlMs = 60000L, cancelHook = IO.unit, owner = Some("alice"))
      byAlice <- store.get("task-alice", Some("alice"))
      byBob   <- store.get("task-alice", Some("bob"))
      byNone  <- store.get("task-alice", None)
      bobCancel <- store.cancel("task-alice", Some("bob"))
      bobUpdate <- store.update("task-alice", Map.empty, Some("bob"))
      after   <- store.get("task-alice", Some("alice"))
    yield
      assert(byAlice.isDefined)
      assertEquals(byBob, None)
      assertEquals(byNone, None)
      assertEquals(bobCancel, None)
      assertEquals(bobUpdate, None)
      assertEquals(after.map(_.status), Some(TaskStatus.Working))
