package ironmcp
package demo

import cats.effect.IO
import io.circe.{Decoder, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*

/** Two tools whose whole job is to show where the type system sits.
  *
  * `greet` refines both arguments. Ask it to repeat a greeting 50 times and
  * the model gets back a readable constraint violation instead of 50 lines —
  * and our handler never runs, because `Repeat` cannot hold 50.
  */
object DemoTools:

  type Repeat = Int :| Interval.Closed[1, 10]

  final case class Greet(name: NonEmptyString, times: Repeat) derives Decoder

  private val greetSchema = Json.obj(
    "type" -> Json.fromString("object"),
    "properties" -> Json.obj(
      "name"  -> Json.obj("type" -> Json.fromString("string"), "description" -> Json.fromString("Who to greet")),
      "times" -> Json.obj(
        "type"        -> Json.fromString("integer"),
        "minimum"     -> Json.fromInt(1),
        "maximum"     -> Json.fromInt(10),
        "description" -> Json.fromString("How many times to repeat the greeting")
      )
    ),
    "required" -> Json.arr(Json.fromString("name"), Json.fromString("times"))
  )

  val greet: ToolDef[Greet] = ToolDef(
    name = "greet",
    description = "Greet someone, one to ten times.",
    inputSchema = greetSchema,
    handler = args =>
      val line = List.fill(args.times)(s"Hello, ${args.name: String}!").mkString(" ")
      IO.pure(ToolOutcome.Structured(line, Json.obj("greeting" -> Json.fromString(line))))
  )

  final case class Describe(port: Port) derives Decoder

  private val describeSchema = Json.obj(
    "type" -> Json.fromString("object"),
    "properties" -> Json.obj(
      "port" -> Json.obj(
        "type"        -> Json.fromString("integer"),
        "minimum"     -> Json.fromInt(1),
        "maximum"     -> Json.fromInt(65535),
        "description" -> Json.fromString("TCP port number")
      )
    ),
    "required" -> Json.arr(Json.fromString("port"))
  )

  val describePort: ToolDef[Describe] = ToolDef(
    name = "describe_port",
    description = "Say whether a TCP port is well-known, registered, or ephemeral.",
    inputSchema = describeSchema,
    handler = args =>
      val port: Int = args.port
      val kind =
        if port < 1024 then "well-known"
        else if port < 49152 then "registered"
        else "ephemeral"
      IO.pure(ToolOutcome.Structured(s"$port is a $kind port", Json.obj("kind" -> Json.fromString(kind))))
  )

  val all: List[ToolDef[?]] = List(greet, describePort)
