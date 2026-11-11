package ironmcp
package fixture

import cats.Show
import cats.effect.{IO, IOApp}
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.transport.Stdio

/** The integration fixture: one stdio server with every capability MCP Inspector's CLI can drive, so it/ checks
  * iron-mcp end to end against a client it did not write. Each piece has a fixed, checkable answer.
  */
object Main extends IOApp.Simple:

  // ---- tools ----------------------------------------------------------------------------------------------------

  type Message = String :| (MinLength[1] & MaxLength[200] DescribedAs "What to echo back")
  type Times   = Int :| (Interval.Closed[1, 5] DescribedAs "How many times to repeat it (1 to 5)")

  final case class Echo(message: Message, times: Option[Times]) derives Decoder, JsonSchemaOf
  final case class Echoed(message: String, times: Int, echo: String) derives Encoder.AsObject, JsonSchemaOf
  object Echoed:
    given Show[Echoed] = _.echo

  final case class Add(a: Double, b: Double) derives Decoder, JsonSchemaOf
  final case class Sum(sum: Double) derives Encoder.AsObject, JsonSchemaOf
  object Sum:
    given Show[Sum] = s => s.sum.toString

  type Steps = Int :| (Interval.Closed[1, 5] DescribedAs "How many steps to report")
  final case class Slow(steps: Steps) derives Decoder, JsonSchemaOf

  final case class NoArgs() derives Decoder, JsonSchemaOf

  private val readOnly = Some(ToolAnnotations(readOnlyHint = Some(true), idempotentHint = Some(true), openWorldHint = Some(false)))

  val tools: ToolSet[IO] = ToolSet.of(
    McpTool[Echo]("echo", "Echoes a message, optionally repeated.", title = Some("Echo"), annotations = readOnly)(args =>
      val times = args.times.fold(1)(t => t)
      IO.pure(Echoed(args.message, times, List.fill(times)(args.message: String).mkString(" ")))
    ),
    McpTool[Add]("add", "Adds two numbers.", annotations = readOnly)(args => IO.pure(Sum(args.a + args.b))),
    McpTool[NoArgs]("fail", "Always fails: the failure comes back as an isError result.")(_ =>
      IO.raiseError[String](RuntimeException("this tool always fails"))
    ),
    McpTool.reporting[IO, Slow]("slow", "Reports progress once per step, then answers.") { (args, emit) =>
      val steps: Int = args.steps
      (1 to steps).toList.foldLeft(IO.unit)((done, step) => done *> emit.progress(step.toDouble, Some(steps.toDouble), Some(s"step $step"))) *>
        IO.pure(s"done after $steps steps")
    }
  )

  // ---- resources ------------------------------------------------------------------------------------------------

  private val ItemPrefix = "fixture://item/"

  val resources: ResourceProvider[IO] = new ResourceProvider[IO]:
    def list(params: ListResourcesParams): IO[ListResourcesResult] =
      IO.pure(ListResourcesResult(
        List(
          Resource(uri = "fixture://readme", name = "readme", title = Some("Read me"), mimeType = Some("text/markdown")),
          Resource(uri = "fixture://data.json", name = "data", mimeType = Some("application/json"))
        ),
        60000L,
        CacheScope.`public`
      ))

    override def templates(params: ListResourcesParams): IO[ListResourceTemplatesResult] =
      IO.pure(ListResourceTemplatesResult(
        List(ResourceTemplate(uriTemplate = "fixture://item/{id}", name = "item", description = Some("One item, by id"), mimeType = Some("text/plain"))),
        60000L,
        CacheScope.`public`
      ))

    def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult] =
      val uri: String = params.uri
      val text: Option[(String, String)] = uri match
        case "fixture://readme"                                       => Some("# iron-mcp fixture\n\nServed for MCP Inspector." -> "text/markdown")
        case "fixture://data.json"                                    => Some("""{"answer":42}""" -> "application/json")
        case item if item.startsWith(ItemPrefix) && item.length > ItemPrefix.length => Some(s"item ${item.drop(ItemPrefix.length)}" -> "text/plain")
        case _                                                        => None
      text match
        case Some((body, mime)) =>
          IO.pure(ReadResourceResult(List(ResourceContents.Text(params.uri, body, Some(mime.refineUnsafe))), 60000L, CacheScope.`public`))
        case None => IO.raiseError(McpException.resourceNotFound(uri))

  // ---- prompts and completions ----------------------------------------------------------------------------------

  private val names = List("Ada", "Alan", "Barbara", "Grace", "Guido")

  val prompts: PromptProvider[IO] = new PromptProvider[IO]:
    def list(params: ListPromptsParams): IO[ListPromptsResult] =
      IO.pure(ListPromptsResult(
        List(Prompt(
          name = "greeting",
          description = Some("Greets someone"),
          arguments = Some(List(
            PromptArgument(name = "name", description = Some("Who to greet"), required = Some(true)),
            PromptArgument(name = "style", description = Some("formal or casual"), required = Some(false))
          ))
        )),
        60000L,
        CacheScope.`public`
      ))

    def get(params: GetPromptParams): IO[GetPromptResult | InputRequiredResult] =
      val arguments = params.arguments.getOrElse(Map.empty)
      if (params.name: String) != "greeting" then
        IO.raiseError(McpException(JsonRpcError(ErrorCode.InvalidParams, s"unknown prompt: ${params.name: String}".refineUnsafe)))
      else
        arguments.get("name").filter(_.nonEmpty) match
          case None => IO.raiseError(McpException(JsonRpcError(ErrorCode.InvalidParams, "greeting needs a name")))
          case Some(name) =>
            val text = if arguments.get("style").contains("formal") then s"Good day, $name." else s"Hi $name!"
            IO.pure(GetPromptResult(List(PromptMessage(Role.user, ContentBlock.Text(text))), description = Some("A greeting")))

  val completions: CompletionProvider[IO] = new CompletionProvider[IO]:
    def complete(params: CompleteParams): IO[CompleteResult] =
      val values = (params.ref, params.argument.name: String) match
        case (CompletionReference.ForPrompt(prompt, _), "name") if (prompt: String) == "greeting" =>
          names.filter(_.toLowerCase.startsWith(params.argument.value.toLowerCase))
        case (CompletionReference.ForPrompt(prompt, _), "style") if (prompt: String) == "greeting" =>
          List("casual", "formal").filter(_.startsWith(params.argument.value))
        case _ => Nil
      IO.pure(CompleteResult(Completion(values, Some(values.size.toLong), Some(false))))

  val server: McpServer[IO] = McpServer[IO](
    info = Implementation(name = "iron-mcp-fixture", version = "1.0.0"),
    instructions = Some("The iron-mcp integration fixture: echo, add, fail and slow tools; fixture:// resources; a greeting prompt."),
    tools = Some(tools),
    resources = Some(resources),
    prompts = Some(prompts),
    completions = Some(completions)
  )

  def run: IO[Unit] = Stdio.serve(server)
