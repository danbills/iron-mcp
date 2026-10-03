package ironmcp
package nasa

import cats.effect.Concurrent
import cats.syntax.all.*
import cats.~>
import io.circe.Json
import org.http4s.{Method, Request, Uri}
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

/** [[ApodOp]] against api.nasa.gov. The key is the interpreter's concern, not the program's; it never appears
  * in an error message, so a failure cannot leak it to the model.
  */
object ApodHttp4s:

  val base: Uri = Uri.unsafeFromString("https://api.nasa.gov/planetary/apod")

  def apply[F[_]](client: Client[F], apiKey: Option[String], base: Uri = base)(using F: Concurrent[F]): ApodOp ~> F =
    new (ApodOp ~> F):
      def apply[A](op: ApodOp[A]): F[A] = op match
        case ApodOp.PictureOf(date) =>
          apiKey match
            case None =>
              F.raiseError(new RuntimeException(
                "NASA_API_KEY is not set. Get a free APOD API key at https://api.nasa.gov and export NASA_API_KEY."))
            case Some(key) =>
              val uri = base.withQueryParam("api_key", key).withQueryParam("thumbs", "true")
                .withOptionQueryParam("date", date.map(d => d: String))
              client.run(Request[F](Method.GET, uri)).use { resp =>
                if resp.status.isSuccess then resp.as[Picture]
                else
                  // Errors come back as {"code", "msg"} or {"error": {"code", "message"}}.
                  resp.as[Json].attempt.flatMap { body =>
                    val cursor  = body.toOption.map(_.hcursor)
                    val message = cursor.flatMap(c =>
                      c.get[String]("msg").orElse(c.downField("error").get[String]("message")).toOption)
                    F.raiseError(new RuntimeException(
                      s"APOD API error ${resp.status.code}: ${message.getOrElse(resp.status.reason)}"))
                  }
              }
