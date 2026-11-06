package ironmcp

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Decoder, JsonObject, parser}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.client.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.transport.Stdio
import munit.CatsEffectSuite

/** Progress notifications from a running tool, through the in-process and stdio transports. */
class ProgressSuite extends CatsEffectSuite:

  final case class Steps(steps: Int :| Interval.Closed[1, 10]) derives Decoder, JsonSchemaOf

  private val countdown = McpTool.reporting[IO, Steps]("countdown", "Counts down, reporting each step.") { (args, emit) =>
    val total: Int = args.steps
    (1 to total).toList.traverse_(i => emit.progress(i.toDouble, Some(total.toDouble), Some(s"step $i of $total"))).as(s"Done after $total steps.")
  }

  private val server = McpServer[IO](Implementation("progress", "0"), tools = Some(ToolSet.of(countdown)))

  private val three = JsonObject("steps" -> 3.asJson)

  private def progressOf(n: JsonRpcMessage.Notification): Option[ProgressParams] =
    Option.when((n.method: String) == "notifications/progress")(n.params.flatMap(_.as[ProgressParams].toOption)).flatten

  test("a reporting tool's progress reaches the client before its result, for the client's progressToken"):
    for
      seen   <- Ref.of[IO, List[ProgressParams]](Nil)
      c      <- McpClient[IO](ClientTransport.inProcess(server, n => seen.update(_ ++ progressOf(n))))
      result <- c.callTool("countdown", three, progressToken = Some(ProgressToken.Text("job-1")))
      got    <- seen.get
    yield
      result match
        case r: CallToolResult => assertEquals(r.content, List(ContentBlock.Text("Done after 3 steps.")))
        case other             => fail(s"unexpected $other")
      assertEquals(got.map(p => (p.progress: Double)), List(1.0, 2.0, 3.0))
      assertEquals(got.map(_.progressToken).distinct, List(ProgressToken.Text("job-1")))
      assertEquals(got.flatMap(_.total).distinct, List(3.0))
      assertEquals(got.flatMap(_.message).map(m => m: String).last, "step 3 of 3")

  test("without a progressToken the tool runs and emits nothing"):
    for
      seen   <- Ref.of[IO, Int](0)
      c      <- McpClient[IO](ClientTransport.inProcess(server, _ => seen.update(_ + 1)))
      result <- c.callTool("countdown", three)
      count  <- seen.get
    yield
      assert(result.isInstanceOf[CallToolResult])
      assertEquals(count, 0)

  test("a plain call through handle still works: the tool just has nowhere to report"):
    McpClient[IO](ClientTransport.inProcess(server)).flatMap(_.callTool("countdown", three, progressToken = Some(ProgressToken.Num(1)))).map {
      case r: CallToolResult => assertEquals(r.isError, Some(false))
      case other             => fail(s"unexpected $other")
    }

  test("over stdio, a request's notifications are written before its reply"):
    val meta = """{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"progressToken":7}"""
    val line = s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"countdown","arguments":{"steps":2},"_meta":$meta}}"""
    Stream.emit(line).covary[IO].through(Stdio.pipe(server)).compile.toList.map { out =>
      val json = out.map(parser.parse(_).toOption.get)
      assertEquals(json.map(_.hcursor.get[String]("method").toOption), List(Some("notifications/progress"), Some("notifications/progress"), None))
      assertEquals(json.last.hcursor.get[Int]("id"), Right(1))
      assertEquals(json.head.hcursor.downField("params").get[Int]("progressToken"), Right(7))
    }
