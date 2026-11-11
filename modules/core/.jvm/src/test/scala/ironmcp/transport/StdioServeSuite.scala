package ironmcp
package transport

import cats.effect.IO
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.McpServer
import munit.CatsEffectSuite

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Stdio.serve and Stdio.run between the real standard streams, swapped for in-memory ones (JVM only: the streams
  * are process-wide, and Scala Native's System.setIn is not to be relied on).
  */
class StdioServeSuite extends CatsEffectSuite:

  private val server = McpServer[IO](info = Implementation(name = "stdio-serve", version = "1"))

  private val discover =
    s"""{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"$LatestProtocolVersion","io.modelcontextprotocol/clientCapabilities":{}}}}"""

  /** Runs `serve` with `input` on stdin, and returns the JSON-RPC lines it wrote to stdout (other suites, running
    * alongside, may print to the swapped stdout too).
    */
  private def served(input: String)(serve: IO[Unit]): IO[List[String]] =
    IO.blocking {
      val (in, out) = (System.in, System.out)
      val written   = ByteArrayOutputStream()
      (in, out, written)
    }.bracket { (_, _, written) =>
      IO.blocking {
        System.setIn(ByteArrayInputStream(input.getBytes(UTF_8)))
        System.setOut(PrintStream(written, true, UTF_8))
      } *> serve *> IO(String(written.toByteArray, UTF_8).linesIterator.filter(_.startsWith("{\"jsonrpc\"")).toList)
    }((in: InputStream, out: PrintStream, _) => IO.blocking { System.setIn(in); System.setOut(out) })

  test("serve answers each line from stdin on stdout, then ends with stdin"):
    served(s"$discover\n{not json\n")(Stdio.serve(server)).map { lines =>
      assertEquals(lines.size, 2)
      assertEquals(parse(lines(0)).flatMap(_.hcursor.downField("result").get[List[String]]("supportedVersions")), Right(List(LatestProtocolVersion: String)))
      assertEquals(parse(lines(1)).flatMap(_.hcursor.downField("error").get[Int]("code")), Right(-32700))
    }

  test("run is serve as a stream, for a main method"):
    served(s"$discover\n")(Stdio.run(server).compile.drain).map(lines => assertEquals(lines.size, 1))
