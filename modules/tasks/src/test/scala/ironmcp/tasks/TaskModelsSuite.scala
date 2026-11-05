package ironmcp
package tasks

import io.circe.parser.parse
import io.circe.syntax.*
import ironmcp.protocol.{CallToolResult, ContentBlock}
import ironmcp.tasks.TaskModels.*
import munit.FunSuite

class TaskModelsSuite extends FunSuite:

  test("TaskStatus roundtrip serialization in lowercase"):
    assertEquals(TaskStatus.Working.asJson.asString, Some("working"))
    assertEquals(TaskStatus.InputRequired.asJson.asString, Some("input_required"))
    assertEquals(TaskStatus.Completed.asJson.asString, Some("completed"))
    assertEquals(TaskStatus.Failed.asJson.asString, Some("failed"))
    assertEquals(TaskStatus.Cancelled.asJson.asString, Some("cancelled"))

    assertEquals(parse("\"working\"").flatMap(_.as[TaskStatus]), Right(TaskStatus.Working))
    assertEquals(parse("\"completed\"").flatMap(_.as[TaskStatus]), Right(TaskStatus.Completed))
    assertEquals(parse("\"cancelled\"").flatMap(_.as[TaskStatus]), Right(TaskStatus.Cancelled))

  test("CreateTaskResult roundtrip serialization"):
    val created = CreateTaskResult(
      resultType = "task",
      taskId = "task-1234",
      status = TaskStatus.Working,
      pollIntervalMs = Some(1500L),
      ttlMs = Some(60000L)
    )
    val json = created.asJson
    assertEquals(json.hcursor.get[String]("resultType"), Right("task"))
    assertEquals(json.hcursor.get[String]("taskId"), Right("task-1234"))
    assertEquals(json.hcursor.get[TaskStatus]("status"), Right(TaskStatus.Working))
    assertEquals(json.hcursor.get[Long]("pollIntervalMs"), Right(1500L))

    val decoded = json.as[CreateTaskResult].toOption.get
    assertEquals(decoded, created)

  test("GetTaskResult with inlined CallToolResult"):
    val callResult = CallToolResult(content = List(ContentBlock.Text("Report generated successfully.")))
    val snapshot = GetTaskResult(
      taskId = "task-5678",
      status = TaskStatus.Completed,
      pollIntervalMs = Some(1000L),
      result = Some(callResult)
    )
    val json = snapshot.asJson
    assertEquals(json.hcursor.get[TaskStatus]("status"), Right(TaskStatus.Completed))

    val decoded = json.as[GetTaskResult].toOption.get
    assertEquals(decoded.status, TaskStatus.Completed)
    val inlinedText = decoded.result.get.content.head match
      case ContentBlock.Text(t, _, _) => t
      case _                          => ""
    assertEquals(inlinedText, "Report generated successfully.")
