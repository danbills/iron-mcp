package ironmcp
package http

import cats.effect.IO
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.testing.TestingLogger

class UnhandledLogSuite extends CatsEffectSuite:

  test("logs the method, the id and the exception, never the params"):
    val logger  = TestingLogger.impl[IO]()
    val message = JsonRpcMessage.Request(RequestId.Num(7L), "tools/call", Some(Json.obj("token" -> Json.fromString("hunter2"))))
    val error   = RuntimeException("boom")
    for
      _      <- UnhandledLog(logger)(message, error)
      logged <- logger.logged
    yield logged match
      case Vector(TestingLogger.ERROR(text, Some(thrown))) =>
        assertEquals(text, "unhandled exception answering tools/call (id 7)")
        assertEquals(thrown, error)
        assert(!text.contains("hunter2"))
      case other => fail(s"expected one error with the exception, got $other")
