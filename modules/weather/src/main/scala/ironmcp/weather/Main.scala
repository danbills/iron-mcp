package ironmcp
package weather

import cats.effect.{IO, IOApp}
import io.circe.Decoder
import io.circe.derivation.{Configuration, ConfiguredEncoder}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import org.http4s.Uri
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import ironmcp.transport.Stdio

/** A stdio MCP server exposing the U.S. National Weather Service (weather.gov)
  * API as tools.
  *
  * The tools' argument types are the schema: `Latitude` / `Longitude` carry
  * their bounds and descriptions in the type, so the advertised JSON Schema and
  * the runtime validation are one declaration that cannot drift apart. Each
  * handler makes plain GETs against api.weather.gov — no SDK, no reflection —
  * decoding responses into case classes and encoding results from them, so no
  * field is read from or written to a raw `Json` by hand.
  */
object Main extends IOApp.Simple:

  // --- tool arguments (the input schema) ------------------------------------

  type LatitudeC  = Interval.Closed[-90.0, 90.0] DescribedAs "WGS84 latitude"
  type LongitudeC = Interval.Closed[-180.0, 180.0] DescribedAs "WGS84 longitude"
  type Latitude   = Double :| LatitudeC
  type Longitude  = Double :| LongitudeC

  // An NWS event name such as "Flood Warning" or "High Wind Watch".
  type EventC = Not[Empty] DescribedAs "NWS event name to filter by, e.g. Flood Warning (optional)"
  type Event  = String :| EventC

  final case class GetForecast(latitude: Latitude, longitude: Longitude) derives Decoder, JsonSchemaOf
  final case class GetConditions(latitude: Latitude, longitude: Longitude) derives Decoder, JsonSchemaOf
  final case class GetAlerts(latitude: Latitude, longitude: Longitude, event: Option[Event]) derives Decoder, JsonSchemaOf

  // --- NWS response models --------------------------------------------------
  //
  // NWS documents are GeoJSON features and feature collections. Field names
  // match the wire; derived decoders ignore the fields we do not model.

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

  // --- tool results (the structured content) --------------------------------

  given Configuration = Configuration.default.withSnakeCaseMemberNames

  final case class ForecastResult(headline: String, periods: List[String]) derives ConfiguredEncoder

  final case class ConditionsResult(
      station: String,
      time: String,
      sky: String,
      pressureHpa: Option[Double],
      pressureTrend: String,
      tempC: Option[Double],
      dewpointC: Option[Double],
      windKmh: Option[Double],
      gustKmh: Option[Double],
      humidityPct: Option[Double],
      visibilityM: Option[Double]
  ) derives ConfiguredEncoder

  final case class AlertResult(
      event: String,
      area: String,
      severity: String,
      effective: String,
      expires: String,
      headline: String
  ) derives ConfiguredEncoder

  final case class AlertsResult(count: Int, alerts: List[AlertResult]) derives ConfiguredEncoder

  // --- helpers --------------------------------------------------------------

  private val nwsBase = "https://api.weather.gov"

  /** GET `url` and decode the body as `A`, naming the URL in any failure. */
  private def get[A: Decoder](client: Client[IO], url: String): IO[A] =
    client
      .expect[A](Uri.unsafeFromString(url))
      .handleErrorWith(err => IO.raiseError(new RuntimeException(s"GET $url failed: ${err.getMessage}", err)))

  /** Resolve a coordinate to its nearest NWS gridpoint, which links to the
    * forecast and the list of observing stations that cover it.
    */
  private def point(client: Client[IO], lat: Double, lon: Double): IO[PointProperties] =
    get[Feature[PointProperties]](client, s"$nwsBase/points/$lat,$lon").map(_.properties)

  /** Turn any failure into a failed [[CallToolResult]] so the model can read and
    * correct it rather than seeing a protocol error.
    */
  private def toolResult(tool: String)(result: IO[CallToolResult]): IO[CallToolResult] =
    result.handleError(err => CallToolResult.failed(s"$tool failed: ${err.getMessage}"))

  private def num(m: Option[Measurement]): Option[Double] = m.flatMap(_.value)

  /** Format an optional measurement value, or "n/a" when absent. */
  private def fmt(v: Option[Double], unit: String, dec: Int): String =
    v.map(d => ("%." + dec + "f").format(d) + unit).getOrElse("n/a")

  /** Describe the change across a series of pressure readings (hPa). */
  private def pressureTrend(readings: List[Double]): String =
    if readings.sizeCompare(2) >= 0 then
      val sorted = readings.sorted
      val delta  = sorted.last - sorted.head
      val span   = s"${readings.size} readings"
      if math.abs(delta) < 0.5 then s"steady (~$span)"
      else if delta > 0 then s"rising +${"%.1f".format(delta)} hPa over $span"
      else s"falling ${"%.1f".format(delta)} hPa over $span"
    else "insufficient data for a trend"

  // --- tools ----------------------------------------------------------------

  private val getForecast = McpTool[GetForecast](
    name = "get_forecast",
    description = "Fetch the NWS (weather.gov) forecast for a latitude/longitude.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
  ) { args =>
    toolResult("get_forecast") {
      EmberClientBuilder.default[IO].build.use { client =>
        for
          pt    <- point(client, args.latitude, args.longitude)
          fcast <- get[Feature[ForecastProperties]](client, pt.forecast.getOrElse(s"$nwsBase/forecasts/auto"))
        yield
          val lines = fcast.properties.periods.map { p =>
            val temp = p.temperature.map(t => s"$t°F").getOrElse("")
            s"${p.name.getOrElse("?")}: ${p.shortForecast.getOrElse("")} $temp".trim
          }
          val result = ForecastResult("NWS forecast", lines)
          CallToolResult.structured((result.headline +: lines).mkString("\n"), result.asJson)
      }
    }
  }

  private val getConditions = McpTool[GetConditions](
    name = "get_conditions",
    description = "Fetch current weather conditions (pressure, temperature, wind, sky) for a latitude/longitude from the nearest NWS observing station.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
  ) { args =>
    toolResult("get_conditions") {
      EmberClientBuilder.default[IO].build.use { client =>
        for
          pt       <- point(client, args.latitude, args.longitude)
          stnUrl   <- IO.fromOption(pt.observationStations)(new RuntimeException("no observation-stations url for this point"))
          stations <- get[FeatureCollection[StationProperties]](client, stnUrl)
          station  <- IO.fromOption(stations.features.headOption.map(_.properties))(
                        new RuntimeException("no observing station found near this point"))
          stnBase   = s"$nwsBase/stations/${station.stationIdentifier}/observations"
          obs      <- get[Feature[ObservationProperties]](client, s"$stnBase/latest").map(_.properties)
          // The trend is a nicety: a failed history fetch leaves it empty rather than failing the tool.
          series   <- get[FeatureCollection[ObservationProperties]](client, s"$stnBase?limit=12")
                        .map(_.features).handleError(_ => Nil)
        yield
          val c = ConditionsResult(
            station = station.name.getOrElse(s"station ${station.stationIdentifier}"),
            time = obs.timestamp.getOrElse("unknown time"),
            sky = obs.textDescription.filter(_.nonEmpty).getOrElse("unknown"),
            pressureHpa = num(obs.barometricPressure).map(_ / 100.0),
            pressureTrend = pressureTrend(series.flatMap(f => num(f.properties.barometricPressure)).map(_ / 100.0)),
            tempC = num(obs.temperature),
            dewpointC = num(obs.dewpoint),
            windKmh = num(obs.windSpeed),
            gustKmh = num(obs.windGust),
            humidityPct = num(obs.relativeHumidity),
            visibilityM = num(obs.visibility)
          )
          val text = List(
            s"Conditions near ${c.station} at ${c.time}:",
            s"  Sky:       ${c.sky}",
            s"  Pressure:  ${fmt(c.pressureHpa, " hPa", 1)}  (trend: ${c.pressureTrend})",
            s"  Temp:      ${fmt(c.tempC, "°C", 0)}  Dewpoint: ${fmt(c.dewpointC, "°C", 0)}",
            s"  Wind:      ${fmt(c.windKmh, " km/h", 0)}  Gust: ${fmt(c.gustKmh, " km/h", 0)}",
            s"  Humidity:  ${fmt(c.humidityPct, "%", 0)}  Visibility: ${fmt(c.visibilityM, " m", 0)}"
          ).mkString("\n")
          CallToolResult.structured(text, c.asJson)
      }
    }
  }

  private val getAlerts = McpTool[GetAlerts](
    name = "get_alerts",
    description = "List active NWS (weather.gov) alerts affecting a latitude/longitude. Optionally filter by event name, e.g. Flood Warning.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
  ) { args =>
    toolResult("get_alerts") {
      EmberClientBuilder.default[IO].build.use { client =>
        val q   = args.event.map(e => s"&event=${java.net.URLEncoder.encode(e, "UTF-8")}").getOrElse("")
        val url = s"$nwsBase/alerts/active?point=${args.latitude},${args.longitude}$q"
        get[FeatureCollection[AlertProperties]](client, url).map { doc =>
          val alerts = doc.features.map { f =>
            val p = f.properties
            AlertResult(
              event = p.event.getOrElse("-"),
              area = p.areaDesc.getOrElse("-"),
              severity = p.severity.getOrElse("-"),
              effective = p.effective.getOrElse("-"),
              expires = p.expires.getOrElse("-"),
              headline = p.headline.getOrElse("-")
            )
          }
          val text =
            if alerts.isEmpty then s"No active alerts near this location${args.event.map(e => s" for $e").getOrElse("")}."
            else ("Active alerts near this location:" +: alerts.map(a => s"  ${a.event}")).mkString("\n")
          CallToolResult.structured(text, AlertsResult(alerts.size, alerts).asJson)
        }
      }
    }
  }

  private val server = McpServer(
    info = Implementation(name = "iron-mcp-weather", version = "0.1.0"),
    instructions = Some("U.S. weather via the National Weather Service (weather.gov)."),
    tools = Some(ToolSet.of(getForecast, getConditions, getAlerts))
  )

  def run: IO[Unit] = Stdio.serve(server)
