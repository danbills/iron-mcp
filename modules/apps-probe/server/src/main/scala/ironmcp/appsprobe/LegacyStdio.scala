package ironmcp
package appsprobe

import cats.effect.{IO, Ref}
import fs2.text
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import ironmcp.server.{McpServer, ServerHandler}
import ironmcp.transport.Wire
import java.nio.charset.StandardCharsets.UTF_8

/** Stdio for clients of the MCP revisions before 2026-07-28 (Codex, Claude
  * Code), as well as 2026 ones. After kg-mcp's `LegacyStdio`.
  *
  * iron-mcp speaks 2026-07-28, which has no `initialize`: each request carries
  * its protocol version and client capabilities in `_meta`. An older client
  * opens with `initialize` and sends requests without that `_meta`. This
  * answers `initialize` and `ping` itself, drops notifications, and gives each
  * request without the 2026 `_meta` the one its client implies (the
  * capabilities it declared in `initialize`). A 2026 request passes through.
  */
object LegacyStdio:

  val Revision                      = "2026-07-28"
  val LegacyRevisions: List[String] = List("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

  private val VersionKey = "io.modelcontextprotocol/protocolVersion"
  private val CapsKey    = "io.modelcontextprotocol/clientCapabilities"

  def serve(server: McpServer[IO], handler: ServerHandler[IO]): IO[Unit] =
    val caps = server.capabilities
    val advertised = Json.obj(
      "tools"      -> caps.tools.map(_ => Json.obj("listChanged" -> false.asJson)).asJson,
      "resources"  -> caps.resources.map(_ => Json.obj()).asJson,
      "extensions" -> server.extensions.asJson
    ).deepDropNullValues
    Ref.of[IO, Json](Json.obj()).flatMap { declared =>
      def reply(line: String): IO[Option[String]] =
        parse(line).toOption.flatMap(_.asObject) match
          case None => forward(line)
          case Some(message) =>
            (message("method").flatMap(_.asString), message("id")) match
              case (Some("initialize"), Some(id)) =>
                val params   = message("params").map(_.hcursor)
                val asked    = params.flatMap(_.get[String]("protocolVersion").toOption)
                val client   = params.flatMap(_.downField("clientInfo").get[String]("name").toOption).getOrElse("unknown")
                val declares = params.flatMap(_.downField("capabilities").focus).filter(_.isObject).getOrElse(Json.obj())
                val answer = Json.obj(
                  "protocolVersion" -> asked.filter(LegacyRevisions.contains).getOrElse(LegacyRevisions.head).asJson,
                  "capabilities"    -> advertised,
                  "serverInfo"      -> Json.obj("name" -> (server.info.name: String).asJson, "version" -> (server.info.version: String).asJson),
                  "instructions"    -> server.instructions.map(i => i: String).asJson
                ).deepDropNullValues
                declared.set(declares) >>
                  Main.log(s"  initialize from $client (protocol ${asked.getOrElse("?")}): capabilities ${declares.noSpaces}") >>
                  IO.pure(Some(result(id, answer)))
              case (Some("ping"), Some(id)) => IO.pure(Some(result(id, Json.obj())))
              case (Some(method), None)     => Main.log(s"  $method (notification)").as(None)
              case (Some(_), Some(_)) =>
                val params = message("params").flatMap(_.asObject).getOrElse(JsonObject.empty)
                val meta   = params("_meta").flatMap(_.asObject).getOrElse(JsonObject.empty)
                if meta.contains(VersionKey) then forward(line)
                else
                  declared.get.flatMap { caps =>
                    val implied = meta.add(VersionKey, Revision.asJson).add(CapsKey, meta(CapsKey).getOrElse(caps))
                    forward(Json.fromJsonObject(message.add("params", Json.fromJsonObject(params.add("_meta", Json.fromJsonObject(implied))))).noSpaces)
                  }
              case (None, _) => IO.pure(None) // a response to a request this server never sends

      def forward(line: String): IO[Option[String]] =
        Wire.decode(line) match
          case Left(error)    => IO.pure(Some(Wire.errorResponse(error)))
          case Right(message) => handler.handleRecovering(message).map(_.map(Wire.encode))

      // Blocking System.in/out, as iron-mcp's Stdio.serve.
      fs2.io
        .readInputStream(IO(System.in), 8192, closeAfterUse = false)
        .through(text.utf8.decode)
        .through(text.lines)
        .filter(_.trim.nonEmpty)
        .evalMap(reply)
        .unNone
        .evalMap(out => IO.blocking { System.out.write((out + "\n").getBytes(UTF_8)); System.out.flush() })
        .compile
        .drain
    }

  private def result(id: Json, value: Json): String =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "result" -> value).noSpaces
