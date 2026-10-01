package ironmcp
package nasa

import cats.effect.{IO, IOApp}
import io.circe.{Decoder, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.circe.jsonDecoder
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.transport.Stdio

/** A stdio MCP server exposing NASA's Astronomy Picture of the Day (APOD) API as
  * tools.
  *
  * The tools' argument types are the schema: `ApodDate` carries its format and
  * description in the type, so the advertised JSON Schema and the runtime
  * validation are one declaration that cannot drift apart. Each handler makes a
  * plain GET against api.nasa.gov — no SDK, no reflection.
  *
  * APOD requires an API key: set `NASA_API_KEY` in the environment before
  * starting the server. Without it, every tool returns a failed result telling
  * the model to obtain a free key at https://api.nasa.gov.
  */
object Main extends IOApp.Simple:

  // An ISO-8601 calendar date such as "2026-09-29". APOD has data back to 1995.
  type ApodDateC = Match["\\d{4}-\\d{2}-\\d{2}"] DescribedAs "ISO-8601 date, e.g. 2026-09-29 (optional; defaults to today)"
  type ApodDate  = String :| ApodDateC

  final case class GetApod(date: Option[ApodDate]) derives Decoder, JsonSchemaOf

  private val apodBase = "https://api.nasa.gov/planetary/apod"

  /** The APOD API key from the environment, if present. */
  private def apiKey: Option[String] =
    sys.env.get("NASA_API_KEY").filter(_.nonEmpty)

  /** Fetch `url` as JSON, surfacing a failed [[CallToolResult]] on any error so
    * the model can read and correct it rather than seeing a protocol error.
    */
  private def getJson(client: org.http4s.client.Client[IO], url: String): IO[Json] =
    client
      .expect[Json](org.http4s.Uri.unsafeFromString(url))
      .attempt
      .flatMap {
        case Right(json) => IO.pure(json)
        case Left(err)   => IO.raiseError(new RuntimeException(s"GET $url failed: ${err.getMessage}", err))
      }

  /** Read a string field, or None when absent. */
  private def strAt(j: Json, field: String): Option[String] =
    j.hcursor.downField(field).focus.flatMap(_.asString)

  private val getApod = McpTool[GetApod](
    name = "get_apod",
    description = "Fetch NASA's Astronomy Picture of the Day for a date (defaults to today), including title, explanation, media URL, and copyright.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
  ) { args =>
    EmberClientBuilder.default[IO].build.use { client =>
      (for
        key <- IO.fromOption(apiKey)(new RuntimeException(
          "NASA_API_KEY is not set. Get a free APOD API key at https://api.nasa.gov and export NASA_API_KEY."))
        dateQ = args.date.map(d => s"&date=$d").getOrElse("")
        doc   <- getJson(client, s"$apodBase?api_key=$key&thumbs=true$dateQ")
      yield
        // On error the API returns {"error": {...}} instead of an image object.
        val errCode = strAt(doc, "code")
        if errCode.isDefined then
          CallToolResult.failed(s"APOD API error ${errCode.get}: ${strAt(doc, "message").getOrElse("unknown")}")
        else
          val title       = strAt(doc, "title").getOrElse("Astronomy Picture of the Day")
          val date        = strAt(doc, "date").getOrElse("unknown date")
          val mediaType   = strAt(doc, "media_type").getOrElse("image")
          val url         = strAt(doc, "url").getOrElse("")
          val hdUrl       = strAt(doc, "hdurl")
          val copyright   = strAt(doc, "copyright")
          val explanation = strAt(doc, "explanation").getOrElse("")
          val extras = List(hdUrl.map(h => s"HD:  $h"), copyright.map(c => s"Credit: $c")).flatten
          val text = (List(s"APOD $date — $title", s"Media: $mediaType", s"URL: $url") ++ extras :+ "" :+ explanation).mkString("\n")
          val structured = Json.obj(
            "date"        -> Json.fromString(date),
            "title"       -> Json.fromString(title),
            "media_type"  -> Json.fromString(mediaType),
            "url"         -> Json.fromString(url),
            "hdurl"       -> hdUrl.map(Json.fromString).getOrElse(Json.Null),
            "copyright"   -> copyright.map(Json.fromString).getOrElse(Json.Null),
            "explanation" -> Json.fromString(explanation)
          )
          CallToolResult.structured(text, structured)
      ).attempt.flatMap {
        case Right(r: CallToolResult) => IO.pure(r)
        case Left(err)                => IO.pure(CallToolResult.failed(s"get_apod failed: ${err.getMessage}"))
      }
    }
  }

  private val server = McpServer(
    info = Implementation(name = "iron-mcp-nasa", version = "0.1.0"),
    instructions = Some("NASA's Astronomy Picture of the Day (APOD) via api.nasa.gov."),
    tools = Some(ToolSet.of(getApod))
  )

  def run: IO[Unit] = Stdio.serve(server)
