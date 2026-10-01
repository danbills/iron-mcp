package ironmcp
package weather

import cats.effect.{IO, IOApp}
import io.circe.{Decoder, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import org.http4s.circe.jsonDecoder
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
  * handler makes plain GETs against api.weather.gov — no SDK, no reflection.
  */
object Main extends IOApp.Simple:

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

  private val nwsBase = "https://api.weather.gov"

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

  /** Resolve a coordinate to its nearest NWS gridpoint, which links to the
    * forecast and the list of observing stations that cover it.
    */
  private def gridpoint(client: org.http4s.client.Client[IO], lat: Double, lon: Double): IO[Json] =
    getJson(client, s"$nwsBase/points/$lat,$lon")

  private val getForecast = McpTool[GetForecast](
    name = "get_forecast",
    description = "Fetch the NWS (weather.gov) forecast for a latitude/longitude.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
  ) { args =>
    EmberClientBuilder.default[IO].build.use { client =>
      for
        points   <- gridpoint(client, args.latitude, args.longitude)
        fcastUrl = points.hcursor.downField("properties").downField("forecast").as[String]
        fcast    <- getJson(client, fcastUrl.getOrElse(s"$nwsBase/forecasts/auto"))
        periods  = fcast.hcursor.downField("properties").downField("periods").as[List[Json]].getOrElse(Nil)
        lines    = periods.map(p =>
          val period  = p.hcursor.downField("name").as[String].getOrElse("?")
          val temp    = p.hcursor.downField("temperature").as[Int].map(t => s"${t}\u00b0F").getOrElse("")
          val summary = p.hcursor.downField("shortForecast").as[String].getOrElse("")
          s"$period: $summary $temp".trim
        )
        headline = fcast.hcursor.downField("title").as[String].getOrElse("NWS forecast")
        text     = (headline +: lines).mkString("\n")
      yield CallToolResult.structured(text, Json.obj("headline" -> Json.fromString(headline), "periods" -> Json.fromValues(lines.map(Json.fromString))))
    }
  }

  // --- small JSON helpers for reading the observations feed -----------------

  private def numAt(j: Json, field: String): Option[Double] =
    // NWS observation values are wrapper objects: {"unitCode": ..., "value": <num|null>}
    j.hcursor.downField(field).downField("value").focus match
      case Some(n) if n.isNumber => n.asNumber.map(_.toDouble)
      case _                     => None

  private def strAt(j: Json, field: String): Option[String] =
    j.hcursor.downField(field).focus.flatMap(_.asString)

  /** The `properties` object of an observation/gridpoint feature, or empty. */
  private def propsOf(j: Json): Json =
    j.hcursor.downField("properties").focus.getOrElse(Json.obj())

  /** Lift an Option to IO, failing with a message when it is empty. */
  private def ioOf[A](oa: Option[A], ifEmpty: => String): IO[A] =
    oa match
      case Some(a) => IO.pure(a)
      case None    => IO.raiseError(new RuntimeException(ifEmpty))

  private val getConditions = McpTool[GetConditions](
    name = "get_conditions",
    description = "Fetch current weather conditions (pressure, temperature, wind, sky) for a latitude/longitude from the nearest NWS observing station.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
  ) { args =>
    EmberClientBuilder.default[IO].build.use { client =>
      (for
        points   <- gridpoint(client, args.latitude, args.longitude)
        stnUrl    = strAt(propsOf(points), "observationStations")
        stations <- ioOf(stnUrl, "no observation-stations url for this point").flatMap(getJson(client, _))
        first     = stations.hcursor.downField("features").as[List[Json]].toOption.flatMap(_.headOption)
        stnId     = first.flatMap(f => strAt(f, "id")).map(_.split("/").lastOption.getOrElse(""))
        stnMeta  <- ioOf(stnId, "no observing station found near this point").flatMap(sid => getJson(client, s"$nwsBase/stations/$sid").attempt.map(_.getOrElse(Json.obj())))
        obs      <- ioOf(stnId, "no observing station").flatMap(sid => getJson(client, s"$nwsBase/stations/$sid/observations/latest"))
        series   <- ioOf(stnId, "no observing station").flatMap(sid => getJson(client, s"$nwsBase/stations/$sid/observations?limit=12").attempt.map(_.getOrElse(Json.arr())))
      yield
        val props    = propsOf(obs)
        val time     = strAt(props, "timestamp").getOrElse("unknown time")
        val station  = strAt(props, "station")
        val sky      = strAt(props, "textDescription").getOrElse("unknown")
        val tempC    = numAt(props, "temperature")
        val dewC     = numAt(props, "dewpoint")
        val pressHpa = numAt(props, "barometricPressure").map(_ / 100.0)
        val windKmh  = numAt(props, "windSpeed")
        val gustKmh  = numAt(props, "windGust")
        val humidity = numAt(props, "relativeHumidity")
        val visM     = numAt(props, "visibility")
        val readings = series.hcursor.downField("features").as[List[Json]].toOption.getOrElse(Nil)
                         .flatMap(f => numAt(propsOf(f), "barometricPressure").map(_ / 100.0))
                         .sorted
        val trend =
          if readings.sizeCompare(2) >= 0 then
            val delta = readings.last - readings.head
            val span  = s"${readings.size} readings"
            if   math.abs(delta) < 0.5 then s"steady (~$span)"
            else if delta > 0         then s"rising +${"%.1f".format(delta)} hPa over $span"
            else                          s"falling ${"%.1f".format(delta)} hPa over $span"
          else "insufficient data for a trend"
        def fmt(v: Option[Double], unit: String, dec: Int): String =
          v.map(d => ("%." + dec + "f").format(d) + unit).getOrElse("n/a")
        val metaName = strAt(propsOf(stnMeta), "name")
        // The observation feed's `station` field is a URL, not a name; prefer the station metadata name.
        val label    = metaName.orElse(station).orElse(stnId.map(id => s"station $id")).getOrElse("this point")
        val text = List(
          s"Conditions near $label at $time:",
          s"  Sky:       $sky",
          s"  Pressure:  ${fmt(pressHpa, " hPa", 1)}  (trend: $trend)",
          s"  Temp:      ${fmt(tempC, "\u00b0C", 0)}  Dewpoint: ${fmt(dewC, "\u00b0C", 0)}",
          s"  Wind:      ${fmt(windKmh, " km/h", 0)}  Gust: ${fmt(gustKmh, " km/h", 0)}",
          s"  Humidity:  ${fmt(humidity, "%", 0)}  Visibility: ${fmt(visM, " m", 0)}"
        ).mkString("\n")
        val structured = Json.obj(
          "station"        -> Json.fromString(label),
          "time"           -> Json.fromString(time),
          "sky"            -> Json.fromString(sky),
          "pressure_hpa"   -> pressHpa.map(Json.fromDoubleOrNull).getOrElse(Json.Null),
          "pressure_trend" -> Json.fromString(trend),
          "temp_c"         -> tempC.map(Json.fromDoubleOrNull).getOrElse(Json.Null),
          "dewpoint_c"     -> dewC.map(Json.fromDoubleOrNull).getOrElse(Json.Null),
          "wind_kmh"       -> windKmh.map(Json.fromDoubleOrNull).getOrElse(Json.Null),
          "gust_kmh"       -> gustKmh.map(Json.fromDoubleOrNull).getOrElse(Json.Null),
          "humidity_pct"   -> humidity.map(Json.fromDoubleOrNull).getOrElse(Json.Null),
          "visibility_m"   -> visM.map(Json.fromDoubleOrNull).getOrElse(Json.Null)
        )
        CallToolResult.structured(text, structured)
      ).attempt.flatMap {
        case Right(r: CallToolResult) => IO.pure(r)
        case Left(err)                => IO.pure(CallToolResult.failed(s"get_conditions failed: ${err.getMessage}"))
      }
    }
  }

  private val getAlerts = McpTool[GetAlerts](
    name = "get_alerts",
    description = "List active NWS (weather.gov) alerts affecting a latitude/longitude. Optionally filter by event name, e.g. Flood Warning.",
    annotations = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(true)))
  ) { args =>
    EmberClientBuilder.default[IO].build.use { client =>
      val q     = args.event.map(e => s"&event=${java.net.URLEncoder.encode(e, "UTF-8")}").getOrElse("")
      val url   = s"$nwsBase/alerts/active?point=${args.latitude},${args.longitude}$q"
      for
        doc    <- getJson(client, url)
        feats   = doc.hcursor.downField("features").as[List[Json]].getOrElse(Nil)
        rows    = feats.map { f =>
          val pr = propsOf(f)
          def s2(field: String): String = strAt(pr, field).getOrElse("-")
          val event    = s2("event")
          val area     = s2("areaDesc")
          val severity = s2("severity")
          val eff      = s2("effective")
          val exp      = s2("expires")
          val head     = s2("headline")
          Json.obj(
            "event"    -> Json.fromString(event),
            "area"     -> Json.fromString(area),
            "severity" -> Json.fromString(severity),
            "effective"-> Json.fromString(eff),
            "expires"  -> Json.fromString(exp),
            "headline" -> Json.fromString(head)
          )
        }
        text =
          if rows.isEmpty then s"No active alerts near this location${args.event.map(e => s" for " + e).getOrElse("")}."
          else ("\n" +: rows.map(r => "  " + r.hcursor.downField("event").focus.get.asString.getOrElse("?"))).mkString("")
        structured = Json.obj("count" -> Json.fromInt(rows.size), "alerts" -> Json.fromValues(rows))
        result = CallToolResult.structured(text, structured)
      yield result
    }.attempt.flatMap {
      case Right(r: CallToolResult) => IO.pure(r)
      case Left(err)                => IO.pure(CallToolResult.failed(s"get_alerts failed: ${err.getMessage}"))
    }
  }

  private val server = McpServer(
    info = Implementation(name = "iron-mcp-weather", version = "0.1.0"),
    instructions = Some("U.S. weather via the National Weather Service (weather.gov)."),
    tools = Some(ToolSet.of(getForecast, getConditions, getAlerts))
  )

  def run: IO[Unit] = Stdio.serve(server)
