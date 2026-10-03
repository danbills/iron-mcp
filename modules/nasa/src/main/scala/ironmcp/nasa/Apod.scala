package ironmcp
package nasa

import cats.InjectK
import cats.free.Free
import io.circe.derivation.{Configuration, ConfiguredDecoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

// An ISO-8601 calendar date such as "2026-09-29". APOD has data back to 1995.
type ApodDateC = Match["\\d{4}-\\d{2}-\\d{2}"] DescribedAs "ISO-8601 date, e.g. 2026-09-29 (optional; defaults to today)"
type ApodDate  = String :| ApodDateC

private val snakeCase: Configuration = Configuration.default.withSnakeCaseMemberNames

/** One Astronomy Picture of the Day, as api.nasa.gov returns it. */
final case class Picture(
    date: Option[String],
    title: Option[String],
    mediaType: Option[String],
    url: Option[String],
    hdurl: Option[String],
    thumbnailUrl: Option[String],
    copyright: Option[String],
    explanation: Option[String]
)

object Picture:
  given ConfiguredDecoder[Picture] =
    given Configuration = snakeCase
    ConfiguredDecoder.derived[Picture]

/** The APOD request as data. Interpreted by [[ApodHttp4s]] against api.nasa.gov, or by any `ApodOp ~> F`. */
enum ApodOp[A]:
  /** The picture for `date`, or today's when absent. */
  case PictureOf(date: Option[ApodDate]) extends ApodOp[Picture]

/** Smart constructor for [[ApodOp]], polymorphic in the coproduct `G` that holds it. */
final class Apod[G[_]](using InjectK[ApodOp, G]):
  def pictureOf(date: Option[ApodDate]): Free[G, Picture] = Free.liftInject[G](ApodOp.PictureOf(date))

object Apod:
  given [G[_]](using InjectK[ApodOp, G]): Apod[G] = new Apod[G]
  def apply[G[_]](using a: Apod[G]): Apod[G]      = a
