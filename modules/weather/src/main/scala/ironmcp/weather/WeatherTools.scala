package ironmcp
package weather

import cats.MonadThrow
import cats.free.Free
import cats.~>
import io.circe.Decoder
import io.circe.derivation.{Configuration, ConfiguredEncoder}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*

// --- tool arguments (the input schema) --------------------------------------

final case class GetForecast(latitude: Latitude, longitude: Longitude) derives Decoder, JsonSchemaOf
final case class GetConditions(latitude: Latitude, longitude: Longitude) derives Decoder, JsonSchemaOf
final case class GetAlerts(latitude: Latitude, longitude: Longitude, event: Option[Event]) derives Decoder, JsonSchemaOf

// --- tool results (the structured content) ----------------------------------

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

/** The weather tools. Each is a program over [[Nws]] — a description of the requests it makes — so the same tool
  * runs against weather.gov ([[NwsHttp4s]]) or canned answers, and composes with other algebras in a larger
  * coproduct.
  */
object WeatherTools:

  def forecast[G[_]](args: GetForecast)(using N: Nws[G]): Free[G, CallToolResult] =
    for
      pt    <- N.point(args.latitude, args.longitude)
      fcast <- N.forecast(pt.forecast)
    yield
      val lines = fcast.periods.map { p =>
        val temp = p.temperature.map(t => s"$t°F").getOrElse("")
        s"${p.name.getOrElse("?")}: ${p.shortForecast.getOrElse("")} $temp".trim
      }
      val result = ForecastResult("NWS forecast", lines)
      CallToolResult.structured((result.headline +: lines).mkString("\n"), result.asJson)

  def conditions[G[_]](args: GetConditions)(using N: Nws[G]): Free[G, CallToolResult] =
    N.point(args.latitude, args.longitude).flatMap { pt =>
      pt.observationStations match
        case None => Free.pure(CallToolResult.failed("get_conditions failed: no observation-stations url for this point"))
        case Some(link) =>
          N.stations(link).flatMap {
            case Nil => Free.pure(CallToolResult.failed("get_conditions failed: no observing station found near this point"))
            case station :: _ =>
              for
                obs     <- N.latestObservation(station.stationIdentifier)
                // The trend is a nicety: a failed history fetch leaves it empty rather than failing the tool.
                history <- N.observationHistory(station.stationIdentifier, 12)
              yield renderConditions(station, obs, history.getOrElse(Nil))
          }
    }

  def alerts[G[_]](args: GetAlerts)(using N: Nws[G]): Free[G, CallToolResult] =
    N.activeAlerts(args.latitude, args.longitude, args.event).map { found =>
      val alerts = found.map { p =>
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

  /** The tools, run through `nws`. Handler failures become `isError` results (see [[McpTool]]), so `F` needs
    * `MonadThrow`; nothing here asks for `IO`.
    */
  def apply[F[_]: MonadThrow](nws: NwsOp ~> F): ToolSet[F] =
    val readOnly = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
    ToolSet.of(
      McpTool[GetForecast](
        name = "get_forecast",
        description = "Fetch the NWS (weather.gov) forecast for a latitude/longitude.",
        annotations = readOnly
      )(forecast[NwsOp](_).foldMap(nws)),
      McpTool[GetConditions](
        name = "get_conditions",
        description = "Fetch current weather conditions (pressure, temperature, wind, sky) for a latitude/longitude from the nearest NWS observing station.",
        annotations = readOnly
      )(conditions[NwsOp](_).foldMap(nws)),
      McpTool[GetAlerts](
        name = "get_alerts",
        description = "List active NWS (weather.gov) alerts affecting a latitude/longitude. Optionally filter by event name, e.g. Flood Warning.",
        annotations = readOnly
      )(alerts[NwsOp](_).foldMap(nws))
    )

  // --- rendering ---------------------------------------------------------------

  private def renderConditions(
      station: StationProperties,
      obs: ObservationProperties,
      history: List[ObservationProperties]
  ): CallToolResult =
    val c = ConditionsResult(
      station = station.name.getOrElse(s"station ${station.stationIdentifier}"),
      time = obs.timestamp.getOrElse("unknown time"),
      sky = obs.textDescription.filter(_.nonEmpty).getOrElse("unknown"),
      pressureHpa = num(obs.barometricPressure).map(_ / 100.0),
      pressureTrend = pressureTrend(history.flatMap(o => num(o.barometricPressure)).map(_ / 100.0)),
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

  private def num(m: Option[Measurement]): Option[Double] = m.flatMap(_.value)

  /** Format an optional measurement value, or "n/a" when absent. */
  private def fmt(v: Option[Double], unit: String, dec: Int): String =
    v.map(d => ("%." + dec + "f").format(d) + unit).getOrElse("n/a")

  /** Describe the change across a series of pressure readings (hPa). */
  private[weather] def pressureTrend(readings: List[Double]): String =
    if readings.sizeCompare(2) >= 0 then
      val sorted = readings.sorted
      val delta  = sorted.last - sorted.head
      val span   = s"${readings.size} readings"
      if math.abs(delta) < 0.5 then s"steady (~$span)"
      else if delta > 0 then s"rising +${"%.1f".format(delta)} hPa over $span"
      else s"falling ${"%.1f".format(delta)} hPa over $span"
    else "insufficient data for a trend"
