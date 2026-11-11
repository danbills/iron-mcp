package ironmcp
package server

import cats.ApplicativeThrow
import cats.effect.IO
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.CatsEffectSuite

/** Emit, OnUnhandled, ServerHandler's defaults and the ToolOutput re-export. */
class ServerPartsSuite extends CatsEffectSuite:

  private val meta = RequestMeta(LatestProtocolVersion, ClientCapabilities.none)

  test("progress with only a value sends no total and no message; a negative one is sent as 0"):
    for
      sent <- IO.ref(List.empty[JsonRpcMessage.Notification])
      emit  = Emit[IO](meta.copy(progressToken = Some(ProgressToken.Num(1))), n => sent.update(_ :+ n))
      _    <- emit.progress(-2)
      _    <- emit.progress(1, None, Some(""))
      got  <- sent.get
    yield
      val params = got.flatMap(_.params)
      assertEquals(params.map(_.hcursor.get[Double]("progress")), List(Right(0.0), Right(1.0)))
      assert(params.forall(p => p.hcursor.downField("total").failed && p.hcursor.downField("message").failed), params.toString)

  test("without a progress token, or from Emit.none, progress sends nothing"):
    for
      sent <- IO.ref(0)
      _    <- Emit[IO](meta, _ => sent.update(_ + 1)).progress(1)
      _    <- Emit.none[IO].progress(1)
      n    <- sent.get
    yield
      assertEquals(n, 0)
      assertEquals(Emit.none[IO].progressToken, None)

  test("OnUnhandled.both runs both hooks, in order"):
    for
      seen <- IO.ref(List.empty[String])
      hook  = OnUnhandled.both[IO]((_, e) => seen.update(_ :+ s"a:${e.getMessage}"), (_, e) => seen.update(_ :+ s"b:${e.getMessage}"))
      _    <- hook(JsonRpcMessage.Notification("x", None), RuntimeException("e"))
      got  <- seen.get
    yield assertEquals(got, List("a:e", "b:e"))

  test("OnUnhandled.reply gives a notification no reply, and a request its error"):
    assertEquals(OnUnhandled.reply(JsonRpcMessage.Notification("x", None), RuntimeException("e")), None)
    assertEquals(
      OnUnhandled.reply(JsonRpcMessage.Request(RequestId.Num(1), "x", None), McpException.resourceNotFound("a://b")),
      Some(JsonRpcMessage.Failure(Some(RequestId.Num(1)), McpException.resourceNotFound("a://b").error))
    )

  test("a ServerHandler that only answers handle gets the emitting variants by default"):
    val echo = new ServerHandler[IO]:
      def handle(message: JsonRpcMessage) = IO.pure(Some(message))
      def handleRecovering(message: JsonRpcMessage)(using ApplicativeThrow[IO]) = IO.pure(None)
    val message = JsonRpcMessage.Success(RequestId.Num(1), Json.obj())
    for
      emitted   <- echo.handleEmitting(message, _ => IO.unit)
      recovered <- echo.handleEmittingRecovering(message, _ => IO.unit)
    yield assertEquals((emitted, recovered), (Some(message), None))

  test("ironmcp.server.ToolOutput is the protocol's ToolOutput, under its old name"):
    assert(ironmcp.server.ToolOutput eq ironmcp.protocol.ToolOutput)
