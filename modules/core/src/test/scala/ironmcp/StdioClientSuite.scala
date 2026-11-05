package ironmcp

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser
import io.circe.{Decoder, JsonObject}
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

import scala.concurrent.duration.*

/** The stdio framing, with the server joined in memory: no process. */
class StdioClientSuite extends CatsEffectSuite:

  final case class Args(text: String :| Not[Empty]) derives Decoder, JsonSchemaOf

  private val server = McpServer[IO](
    info = Implementation(name = "stdio-suite", version = "0.1.0"),
    tools = Some(ToolSet.of(McpTool[Args]("shout", "Shouts.")(args => IO.pure((args.text: String).toUpperCase))))
  )

  /** A client joined to `Stdio.pipe(server)`: the same lines a process would exchange. */
  private def joined: Resource[IO, McpClient[IO]] =
    for
      toServer  <- Resource.eval(Queue.unbounded[IO, Option[String]])
      transport <- ClientTransport.lines[IO](
                     Stream.fromQueueNoneTerminated(toServer).through(Stdio.pipe(server)),
                     line => toServer.offer(Some(line)),
                     _ => IO.unit
                   )
      client <- Resource.eval(McpClient[IO](transport))
    yield client

  test("a tool call over the stdio framing"):
    joined.use(_.callTool("shout", JsonObject("text" -> "hi".asJson))).map {
      case r: CallToolResult => assertEquals(r.content, List(ContentBlock.Text("HI")))
      case other             => fail(s"unexpected $other")
    }

  test("concurrent requests on one connection"):
    joined.use(c => (1 to 20).toList.parTraverse(i => c.callTool("shout", JsonObject("text" -> s"n$i".asJson)))).map { results =>
      assertEquals(results.size, 20)
    }

  /** A hand-written peer, to control order and timing: it answers the second request first. */
  private def peer(script: (Queue[IO, Option[String]], List[String]) => IO[Unit]): Resource[IO, (McpClient[IO], Queue[IO, Option[String]])] =
    for
      fromServer <- Resource.eval(Queue.unbounded[IO, Option[String]])
      received   <- Resource.eval(Queue.unbounded[IO, String])
      transport <- ClientTransport.lines[IO](
                     Stream.fromQueueNoneTerminated(fromServer),
                     line => received.offer(line),
                     n => fromServer.offer(Some(s"""{"seen":"${n.method: String}"}""")).void
                   )
      _      <- Stream.repeatEval(received.take).sliding(2).take(1).evalMap(c => script(fromServer, c.toList)).compile.drain.background
      client <- Resource.eval(McpClient[IO](transport))
    yield (client, fromServer)

  private def idOf(line: String) = parser.parse(line).flatMap(_.hcursor.get[Long]("id")).toOption.get

  private def reply(id: Long, text: String) =
    s"""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"$text"}]}}"""

  // The replies below leave out resultType, as a server on an earlier revision
  // would; the client must read that as "complete".
  test("replies are matched by id, whatever order they arrive in"):
    peer { (out, requests) =>
      val List(first, second) = requests.map(idOf): @unchecked
      out.offer(Some(reply(second, "second"))) >> out.offer(Some(reply(first, "first")))
    }.use { (c, _) =>
      (c.callTool("x"), c.callTool("y")).parTupled
    }.map { (x, y) =>
      val texts = List(x, y).collect { case r: CallToolResult => r.content }
      assertEquals(texts.flatten.toSet, Set(ContentBlock.Text("first"), ContentBlock.Text("second")))
    }

  test("pending requests fail with Closed when the server's stream ends"):
    peer((out, _) => out.offer(None)).use { (c, _) =>
      (c.callTool("x").attempt, c.callTool("y").attempt).parTupled
    }.map { (x, y) =>
      assert(x.left.exists(_.isInstanceOf[McpClientError.Closed]), x.toString)
      assert(y.left.exists(_.isInstanceOf[McpClientError.Closed]), y.toString)
    }

  test("a cancelled request is withdrawn with notifications/cancelled"):
    for
      sent <- Queue.unbounded[IO, String]
      _ <- (for
             incoming  <- Resource.eval(Queue.unbounded[IO, Option[String]])
             transport <- ClientTransport.lines[IO](Stream.fromQueueNoneTerminated(incoming), sent.offer, _ => IO.unit)
             client    <- Resource.eval(McpClient[IO](transport))
           yield client).use(c => c.callTool("slow").timeout(100.millis).attempt)
      lines <- sent.tryTakeN(None)
    yield
      assertEquals(lines.size, 2)
      val cancelled = parser.parse(lines(1)).toOption.get.hcursor
      assertEquals(cancelled.get[String]("method"), Right("notifications/cancelled"))
      assertEquals(cancelled.downField("params").get[Long]("requestId").toOption, Some(idOf(lines(0))))

  test("server notifications go to the callback"):
    for
      seen <- Deferred[IO, String]
      _ <- (for
             incoming  <- Resource.eval(Queue.unbounded[IO, Option[String]])
             _         <- ClientTransport.lines[IO](Stream.fromQueueNoneTerminated(incoming), _ => IO.unit, n => seen.complete(n.method).void)
           yield incoming).use(in =>
             in.offer(Some("""{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info","data":"hello"}}""")) >> seen.get
           )
      method <- seen.get
    yield assertEquals(method, "notifications/message")
