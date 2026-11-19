package ironmcp
package tasks

import cats.ApplicativeThrow
import cats.effect.Concurrent
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.{McpTool, ToolOutput}

/** What a tool running as a task can do beyond returning: ask its caller for
  * input mid-flight, and say where it is.
  *
  * Asking moves the task to `input_required` with the requests in its
  * snapshot; the caller answers with `tasks/update`, and the task carries on
  * where it stopped, with everything it had done kept. This is the Tasks
  * extension's answer to what a plain tool's `input_required` result cannot do
  * without starting over.
  */
trait TaskContext[F[_]]:
  def taskId: String

  /** Asks the caller and waits for the answers, keyed as asked. A key the
    * caller left out is missing from the map. Asks from one task are answered
    * one at a time.
    */
  def ask(requests: Map[String, InputRequest]): F[Map[String, Json]]

  /** The task's `statusMessage`, shown to a caller polling `tasks/get`. */
  def status(message: String): F[Unit]

object TaskContext:

  /** A context over `store` for the running task `taskId`. */
  def apply[F[_]](taskId: String, store: TaskStore[F])(using F: Concurrent[F]): F[TaskContext[F]] =
    Mutex[F].map { mutex =>
      val id = taskId
      new TaskContext[F]:
        val taskId: String = id
        def ask(requests: Map[String, InputRequest]): F[Map[String, Json]] =
          mutex.lock.surround(
            store.awaitInput(id, requests.view.mapValues(_.asJson).toMap)
              .flatMap(F.fromOption(_, TaskInputUnavailable(s"task $id ended before its input was given")))
          )
        def status(message: String): F[Unit] = store.setStatusMessage(id, message)
    }

  /** For a call that is not running as a task (its client did not declare
    * the extension): asking fails, and status goes nowhere.
    */
  def unavailable[F[_]](tool: String)(using F: ApplicativeThrow[F]): TaskContext[F] = new TaskContext[F]:
    val taskId: String = ""
    def ask(requests: Map[String, InputRequest]): F[Map[String, Json]] =
      F.raiseError(TaskInputUnavailable(s"$tool asks for input while it runs, which needs a client that runs it as a task (io.modelcontextprotocol/tasks)"))
    def status(message: String): F[Unit] = F.unit

/** A task could not get the input it asked for. */
final case class TaskInputUnavailable(reason: String) extends RuntimeException(reason)

/** A tool that may ask its caller for input while it runs, as a task.
  *
  * `mcpTool` is how it is listed and how it runs for a client that does not
  * declare the Tasks extension (its handler gets [[TaskContext.unavailable]],
  * so a run that asks comes back as a tool error). Put it in the server's
  * `ToolSet`, and give the `TaskTool` to [[TaskServer]] as one of its
  * `inputTools`, which runs it in the background with a real context.
  */
final case class TaskTool[F[_]](mcpTool: McpTool[F], run: (CallToolParams, TaskContext[F]) => F[CallToolResult]):
  def name: String = mcpTool.definition.name

object TaskTool:

  /** `TaskTool[IO, Args](name, description) { (args, task) => ... }`. As with
    * `McpTool.reporting`, the effect is named up front because the handler's
    * `task` is typed by it.
    */
  def apply[F[_], A](
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString] = None,
      annotations: Option[ToolAnnotations] = None
  ): Builder[F, A] =
    Builder(name, description, title, annotations)

  final class Builder[F[_], A] private[TaskTool] (
      name: ToolName,
      description: NonEmptyString,
      title: Option[NonEmptyString],
      annotations: Option[ToolAnnotations]
  ):
    def apply[O](handler: (A, TaskContext[F]) => F[O])(using
        F: ApplicativeThrow[F],
        decoder: Decoder[A],
        schema: JsonSchemaOf[A],
        output: ToolOutput[O]
    ): TaskTool[F] =
      def tool(task: TaskContext[F]): McpTool[F] = McpTool[A](name, description, title, annotations)(handler(_, task))
      TaskTool(
        tool(TaskContext.unavailable[F](name)),
        (params, task) => tool(task).invoke(params).map(complete)
      )

  /** A tool built by `McpTool.apply` never answers `input_required`; should one
    * reach here, it is reported rather than lost.
    */
  private[tasks] def complete(result: CallToolResult | InputRequiredResult): CallToolResult = result match
    case done: CallToolResult => done
    case _: InputRequiredResult =>
      CallToolResult.failed("a tool running as a task asks through its TaskContext, not with input_required")
