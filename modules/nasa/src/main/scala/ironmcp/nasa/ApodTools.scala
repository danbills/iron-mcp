package ironmcp
package nasa

import cats.MonadThrow
import cats.free.Free
import cats.~>
import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*

final case class GetApod(date: Option[ApodDate]) derives Decoder, JsonSchemaOf

final case class ApodResult(
    date: String,
    title: String,
    media_type: String,
    url: String,
    hdurl: Option[String],
    copyright: Option[String],
    explanation: String
) derives Encoder.AsObject

/** The APOD tool: a program over [[Apod]], run against api.nasa.gov ([[ApodHttp4s]]) or canned answers. */
object ApodTools:

  def apod[G[_]](args: GetApod)(using A: Apod[G]): Free[G, CallToolResult] =
    A.pictureOf(args.date).map(render)

  /** Handler failures (no key, API errors) become `isError` results via [[McpTool]]. */
  def apply[F[_]: MonadThrow](apod: ApodOp ~> F): ToolSet[F] =
    ToolSet.of(
      McpTool[GetApod](
        name = "get_apod",
        description = "Fetch NASA's Astronomy Picture of the Day for a date (defaults to today), including title, explanation, media URL, and copyright.",
        annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
      )(ApodTools.apod[ApodOp](_).foldMap(apod))
    )

  private[nasa] def render(p: Picture): CallToolResult =
    val result = ApodResult(
      date = p.date.getOrElse("unknown date"),
      title = p.title.getOrElse("Astronomy Picture of the Day"),
      media_type = p.mediaType.getOrElse("image"),
      url = p.url.getOrElse(""),
      hdurl = p.hdurl,
      copyright = p.copyright.map(_.trim),
      explanation = p.explanation.getOrElse("")
    )
    val extras = List(result.hdurl.map(h => s"HD:  $h"), result.copyright.map(c => s"Credit: $c")).flatten
    val text = (List(s"APOD ${result.date} — ${result.title}", s"Media: ${result.media_type}", s"URL: ${result.url}")
      ++ extras :+ "" :+ result.explanation).mkString("\n")
    CallToolResult.structured(text, result.asJson)
