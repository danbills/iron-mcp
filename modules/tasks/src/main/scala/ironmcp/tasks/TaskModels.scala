package ironmcp
package tasks

import io.circe.*
import ironmcp.protocol.{CallToolResult, JsonRpcError}

/** The Tasks Extension (SEP-2663, Handbook §7.3).
  *
  * Enables long-running asynchronous operations with server-directed task handles,
  * polling via `tasks/get`, cooperative cancellation via `tasks/cancel`, and
  * mid-flight input via `tasks/update`.
  */
object TaskModels:

  /** The Tasks capability extension identifier. */
  val CapabilityName: String = "io.modelcontextprotocol/tasks"

  /** Task status lifecycle states. */
  enum TaskStatus(val wire: String):
    case Working       extends TaskStatus("working")
    case InputRequired extends TaskStatus("input_required")
    case Completed     extends TaskStatus("completed")
    case Failed        extends TaskStatus("failed")
    case Cancelled     extends TaskStatus("cancelled")

    def isTerminal: Boolean = this match
      case Completed | Failed | Cancelled => true
      case Working | InputRequired       => false

  object TaskStatus:
    given Codec[TaskStatus] = Codec.from(
      Decoder.decodeString.emap {
        case "working"        => Right(Working)
        case "input_required" => Right(InputRequired)
        case "completed"      => Right(Completed)
        case "failed"         => Right(Failed)
        case "cancelled"      => Right(Cancelled)
        case other            => Left(s"Unknown TaskStatus: '$other'")
      },
      Encoder.encodeString.contramap(_.wire)
    )

  /** Returned by `tools/call` when an operation is executed as an asynchronous task. */
  final case class CreateTaskResult(
      resultType: String = "task",
      taskId: String,
      status: TaskStatus = TaskStatus.Working,
      pollIntervalMs: Option[Long] = None,
      ttlMs: Option[Long] = None
  )

  object CreateTaskResult:
    given Codec[CreateTaskResult] = Codec.AsObject.derived

  final case class GetTaskParams(taskId: String)

  object GetTaskParams:
    given Codec[GetTaskParams] = Codec.AsObject.derived

  /** Snapshot returned by `tasks/get`.
    *
    * When status is `Completed`, the final `CallToolResult` is inlined in `result`
    * (there is no separate `tasks/result` endpoint).
    */
  final case class GetTaskResult(
      taskId: String,
      status: TaskStatus,
      pollIntervalMs: Option[Long] = None,
      ttlMs: Option[Long] = None,
      createdAt: Option[Long] = None,
      lastUpdatedAt: Option[Long] = None,
      result: Option[CallToolResult] = None,
      error: Option[JsonRpcError] = None,
      inputRequest: Option[Json] = None
  )

  object GetTaskResult:
    given Codec[GetTaskResult] = Codec.AsObject.derived

  final case class CancelTaskParams(taskId: String)

  object CancelTaskParams:
    given Codec[CancelTaskParams] = Codec.AsObject.derived

  final case class CancelTaskResult(
      taskId: String,
      status: TaskStatus = TaskStatus.Cancelled
  )

  object CancelTaskResult:
    given Codec[CancelTaskResult] = Codec.AsObject.derived

  final case class UpdateTaskParams(
      taskId: String,
      inputResponses: Map[String, Json] = Map.empty
  )

  object UpdateTaskParams:
    given Codec[UpdateTaskParams] = Codec.AsObject.derived

  final case class UpdateTaskResult(
      taskId: String,
      status: TaskStatus
  )

  object UpdateTaskResult:
    given Codec[UpdateTaskResult] = Codec.AsObject.derived

  /** Result returned by polymorphic tool invocations under the Tasks extension. */
  type ToolCallOrTask = CallToolResult | CreateTaskResult
