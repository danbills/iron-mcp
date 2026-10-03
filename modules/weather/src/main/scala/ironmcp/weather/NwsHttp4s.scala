package ironmcp
package weather

import cats.effect.Concurrent
import cats.syntax.all.*
import cats.~>
import io.circe.Decoder
import org.http4s.Uri
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

/** [[NwsOp]] against api.weather.gov — plain GETs, no SDK, no reflection. Needs
  * only `Concurrent` (for decoding JSON bodies).
  */
object NwsHttp4s:

  val base = "https://api.weather.gov"

  def apply[F[_]: Concurrent](client: Client[F], base: String = base): NwsOp ~> F = new (NwsOp ~> F):

    /** GET `url` and decode the body as `A`, naming the URL in any failure. */
    private def get[A: Decoder](url: String): F[A] =
      Uri
        .fromString(url)
        .liftTo[F]
        .flatMap(client.expect[A](_))
        .adaptError(err => new RuntimeException(s"GET $url failed: ${err.getMessage}", err))

    def apply[A](op: NwsOp[A]): F[A] = op match
      case NwsOp.Point(lat, lon) =>
        get[Feature[PointProperties]](s"$base/points/$lat,$lon").map(_.properties)
      case NwsOp.Forecast(link) =>
        get[Feature[ForecastProperties]](link.getOrElse(s"$base/forecasts/auto")).map(_.properties)
      case NwsOp.Stations(link) =>
        get[FeatureCollection[StationProperties]](link).map(_.features.map(_.properties))
      case NwsOp.LatestObservation(id) =>
        get[Feature[ObservationProperties]](s"$base/stations/$id/observations/latest").map(_.properties)
      case NwsOp.ObservationHistory(id, limit) =>
        get[FeatureCollection[ObservationProperties]](s"$base/stations/$id/observations?limit=$limit")
          .map(_.features.map(_.properties))
          .attempt
          .map(_.leftMap(_.getMessage))
      case NwsOp.ActiveAlerts(lat, lon, event) =>
        val filter = event.map(e => s"&event=${java.net.URLEncoder.encode(e, "UTF-8")}").getOrElse("")
        get[FeatureCollection[AlertProperties]](s"$base/alerts/active?point=$lat,$lon$filter").map(_.features.map(_.properties))
