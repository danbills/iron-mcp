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
      created <- store.create("task-1", pollIntervalMs = 500L, ttlMs = 60000L, cancelHook = IO.unit)
      _ = assertEquals(created.taskId, "task-1")
      _ = assertEquals(created.status, TaskStatus.Working)

      initialSnap <- store.get("task-1")
      _ = assertEquals(initialSnap.map(_.status), Some(TaskStatus.Working))

      callRes = CallToolResult(content = List(ContentBlock.Text("Done!")))
      _ <- store.complete("task-1", callRes)

      completedSnap <- store.get("task-1")
      _ = assertEquals(completedSnap.map(_.status), Some(TaskStatus.Completed))
      _ = assertEquals(completedSnap.flatMap(_.result), Some(callRes))
    yield ()

  test("TaskStore cancellation triggers cancelHook and sets status to Cancelled"):
    for
      cancelledHookRan <- Ref.of[IO, Boolean](false)
      store <- TaskStore.inMemory[IO]()
      _ <- store.create("task-cancel", pollIntervalMs = 500L, ttlMs = 60000L, cancelHook = cancelledHookRan.set(true))

      cancelRes <- store.cancel("task-cancel")
      _ = assertEquals(cancelRes.map(_.status), Some(TaskStatus.Cancelled))

      hookTriggered <- cancelledHookRan.get
      _ = assert(hookTriggered)

      snap <- store.get("task-cancel")
      _ = assertEquals(snap.map(_.status), Some(TaskStatus.Cancelled))
    yield ()

  test("Terminal state is immutable"):
    for
      store <- TaskStore.inMemory[IO]()
      _ <- store.create("task-terminal", pollIntervalMs = 500L, ttlMs = 60000L, cancelHook = IO.unit)
      _ <- store.complete("task-terminal", CallToolResult(content = Nil))

      // Attempting to fail or cancel a completed task does not alter its terminal status
      _ <- store.fail("task-terminal", JsonRpcError(ironmcp.protocol.ErrorCode.InternalError, "too late".assume[Not[Empty]]))
      snap <- store.get("task-terminal")
      _ = assertEquals(snap.map(_.status), Some(TaskStatus.Completed))
    yield ()
