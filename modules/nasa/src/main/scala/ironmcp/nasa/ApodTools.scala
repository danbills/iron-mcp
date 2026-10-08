package ironmcp
package nasa

import cats.{MonadThrow, Show}
import cats.free.Free
import cats.~>
import io.circe.{Decoder, Encoder}
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
) derives Encoder.AsObject, JsonSchemaOf

object ApodResult:
  given Show[ApodResult] = r =>
    val extras = List(r.hdurl.map(h => s"HD:  $h"), r.copyright.map(c => s"Credit: $c")).flatten
    (List(s"APOD ${r.date} — ${r.title}", s"Media: ${r.media_type}", s"URL: ${r.url}") ++ extras :+ "" :+ r.explanation)
      .mkString("\n")

/** The APOD tool: a program over [[Apod]], run against api.nasa.gov ([[ApodHttp4s]]) or canned answers. */
object ApodTools:

  def apod[G[_]](args: GetApod)(using A: Apod[G]): Free[G, ApodResult] =
    A.pictureOf(args.date).map(resultOf)

  /** Handler failures (no key, API errors) become `isError` results via [[McpTool]]. */
  def apply[F[_]: MonadThrow](apod: ApodOp ~> F): ToolSet[F] =
    ToolSet.of(
      McpTool[GetApod](
        name = "get_apod",
        description = "Fetch NASA's Astronomy Picture of the Day for a date (defaults to today), including title, explanation, media URL, and copyright.",
        annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
      )(ApodTools.apod[ApodOp](_).foldMap(apod))
    )

  private[nasa] def resultOf(p: Picture): ApodResult =
    ApodResult(
      date = p.date.getOrElse("unknown date"),
      title = p.title.getOrElse("Astronomy Picture of the Day"),
      media_type = p.mediaType.getOrElse("image"),
      url = p.url.getOrElse(""),
      hdurl = p.hdurl,
      copyright = p.copyright.map(_.trim),
      explanation = p.explanation.getOrElse("")
    )
