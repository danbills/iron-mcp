package ironmcp
package server

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, JsonObject}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import munit.CatsEffectSuite

/** McpTool's three builders and ToolSet: every path a call can take. */
class McpToolSuite extends CatsEffectSuite:

  final case class Count(n: Int :| Positive) derives Decoder, JsonSchemaOf

  private val meta = RequestMeta(LatestProtocolVersion, ClientCapabilities.none)

  private def params(name: String, arguments: (String, io.circe.Json)*): CallToolParams =
    CallToolParams(name.refineUnsafe[ToolNameC], meta, Some(JsonObject(arguments*)))

  private def result(r: CallToolResult | InputRequiredResult): CallToolResult = r match
    case c: CallToolResult => c
    case other             => fail(s"not a tool result: $other")

  private def text(r: CallToolResult): String = r.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString

  private val boom = McpTool[Count]("boom", "Fails.")(_ => IO.raiseError[String](RuntimeException("kaput")))

  private val reporting = McpTool.reporting[IO, Count]("count", "Counts.", title = Some("Count")) { (args, emit) =>
    emit.progress(1, Some(args.n.toDouble)).as((args.n: Int).toString)
  }

  private val reportingBoom = McpTool.reporting[IO, Count]("count_boom", "Fails while counting.")((_, _) => IO.raiseError[String](RuntimeException("lost count")))

  private given RequestStateCodec = RequestStateCodec.unprotected

  private val interactiveBoom =
    McpTool.interactive[Count]("ask_boom", "Fails while asking.")((_, _) => IO.raiseError[Interaction[String]](RuntimeException("no answer")))

  test("a handler that raises is an isError result naming the tool"):
    boom.invoke(params("boom", "n" -> 1.asJson)).map(result).map { r =>
      assertEquals(r.isError, Some(true))
      assertEquals(text(r), "boom failed: kaput")
    }

  test("a reporting tool runs without a sink when invoked directly, and with one when given"):
    for
      plain <- reporting.invoke(params("count", "n" -> 3.asJson)).map(result)
      sent  <- IO.ref(List.empty[JsonRpcMessage.Notification])
      withToken = params("count", "n" -> 3.asJson).copy(_meta = meta.copy(progressToken = Some(ProgressToken.Num(7))))
      _     <- reporting.run(withToken, Emit(withToken._meta, n => sent.update(_ :+ n)))
      got   <- sent.get
    yield
      assertEquals(text(plain), "3")
      assertEquals(reporting.definition.title.map(t => t: String), Some("Count"))
      assertEquals(got.map(_.method: String), List("notifications/progress"))

  test("a reporting tool turns bad arguments and a failing handler into isError results"):
    for
      bad    <- reporting.run(params("count", "n" -> 0.asJson), Emit.none[IO]).map(result)
      failed <- reportingBoom.run(params("count_boom", "n" -> 1.asJson), Emit.none[IO]).map(result)
    yield
      assertEquals(bad.isError, Some(true))
      assertEquals(text(failed), "count_boom failed: lost count")

  test("an interactive tool refuses bad arguments before its handler runs, and reports a failing handler"):
    for
      bad    <- interactiveBoom.invoke(params("ask_boom", "n" -> (-1).asJson)).map(result)
      failed <- interactiveBoom.invoke(params("ask_boom", "n" -> 1.asJson)).map(result)
    yield
      assertEquals(bad.isError, Some(true))
      assertEquals(text(failed), "ask_boom failed: no answer")

  test("ToolSet.call runs a tool with no sink; an unknown name is McpException -32602"):
    val tools = ToolSet.of(reporting)
    for
      called  <- tools.call(params("count", "n" -> 2.asJson)).map(result)
      unknown <- tools.call(params("missing")).attempt
    yield
      assertEquals(text(called), "2")
      unknown match
        case Left(McpException(error)) => assertEquals(error.code, ErrorCode.InvalidParams)
        case other                     => fail(s"not an unknown-tool error: $other")
