package ironmcp
package weather

import cats.InjectK
import cats.free.Free
import io.circe.Decoder
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

// --- coordinates and filters (shared by the tool arguments and the requests) --

type LatitudeC  = Interval.Closed[-90.0, 90.0] DescribedAs "WGS84 latitude"
type LongitudeC = Interval.Closed[-180.0, 180.0] DescribedAs "WGS84 longitude"
type Latitude   = Double :| LatitudeC
type Longitude  = Double :| LongitudeC

// An NWS event name such as "Flood Warning" or "High Wind Watch".
type EventC = Not[Empty] DescribedAs "NWS event name to filter by, e.g. Flood Warning (optional)"
type Event  = String :| EventC

// --- NWS response models ------------------------------------------------------
//
// NWS documents are GeoJSON features and feature collections. Field names match
// the wire; derived decoders ignore the fields we do not model.

final case class Feature[P](properties: P) derives Decoder
final case class FeatureCollection[P](features: List[Feature[P]]) derives Decoder

/** `/points/{lat},{lon}` — resolves a coordinate to its grid and links onward. */
final case class PointProperties(forecast: Option[String], observationStations: Option[String]) derives Decoder

/** A member of the point's `observationStations` collection. */
final case class StationProperties(stationIdentifier: String, name: Option[String]) derives Decoder

/** A measurement in an observation: `{"unitCode": "...", "value": <num|null>}`. */
final case class Measurement(value: Option[Double]) derives Decoder

/** `/stations/{id}/observations/latest`, and each entry of `.../observations`. */
final case class ObservationProperties(
    timestamp: Option[String],
    textDescription: Option[String],
    temperature: Option[Measurement],
    dewpoint: Option[Measurement],
    barometricPressure: Option[Measurement],
    windSpeed: Option[Measurement],
    windGust: Option[Measurement],
    relativeHumidity: Option[Measurement],
    visibility: Option[Measurement]
) derives Decoder

/** The point's `forecast` link (or `/forecasts/auto`). */
final case class Period(name: Option[String], temperature: Option[Int], shortForecast: Option[String]) derives Decoder
final case class ForecastProperties(periods: List[Period]) derives Decoder

/** `/alerts/active?point=...` */
final case class AlertProperties(
    event: Option[String],
    areaDesc: Option[String],
    severity: Option[String],
    effective: Option[String],
    expires: Option[String],
    headline: Option[String]
) derives Decoder

// --- the algebra ----------------------------------------------------------------

/** The weather.gov requests the tools make, as data. Interpreted by [[NwsHttp4s]]
  * against the live API, or by any `NwsOp ~> F` (the tests use canned answers).
  */
enum NwsOp[A]:
  case Point(latitude: Latitude, longitude: Longitude) extends NwsOp[PointProperties]
  /** The forecast behind a point's `forecast` link; the interpreter falls back to `/forecasts/auto`. */
  case Forecast(link: Option[String]) extends NwsOp[ForecastProperties]
  /** The observing stations behind a point's `observationStations` link, nearest first. */
  case Stations(link: String) extends NwsOp[List[StationProperties]]
  case LatestObservation(stationId: String) extends NwsOp[ObservationProperties]
  /** Recent observations. A failure is a value here, not an error: the history is a nicety, and the program
    * decides what its absence means.
    */
  case ObservationHistory(stationId: String, limit: Int) extends NwsOp[Either[String, List[ObservationProperties]]]
  case ActiveAlerts(latitude: Latitude, longitude: Longitude, event: Option[Event]) extends NwsOp[List[AlertProperties]]

/** Smart constructors for [[NwsOp]], polymorphic in the coproduct `G` that holds it. */
final class Nws[G[_]](using InjectK[NwsOp, G]):
  def point(latitude: Latitude, longitude: Longitude): Free[G, PointProperties] =
    Free.liftInject[G](NwsOp.Point(latitude, longitude))
  def forecast(link: Option[String]): Free[G, ForecastProperties] = Free.liftInject[G](NwsOp.Forecast(link))
  def stations(link: String): Free[G, List[StationProperties]]  = Free.liftInject[G](NwsOp.Stations(link))
  def latestObservation(stationId: String): Free[G, ObservationProperties] =
    Free.liftInject[G](NwsOp.LatestObservation(stationId))
  def observationHistory(stationId: String, limit: Int): Free[G, Either[String, List[ObservationProperties]]] =
    Free.liftInject[G](NwsOp.ObservationHistory(stationId, limit))
  def activeAlerts(latitude: Latitude, longitude: Longitude, event: Option[Event]): Free[G, List[AlertProperties]] =
    Free.liftInject[G](NwsOp.ActiveAlerts(latitude, longitude, event))

object Nws:
  given [G[_]](using InjectK[NwsOp, G]): Nws[G] = new Nws[G]
  def apply[G[_]](using n: Nws[G]): Nws[G]      = n
