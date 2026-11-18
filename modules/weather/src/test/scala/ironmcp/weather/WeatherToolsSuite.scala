package ironmcp
package weather

import cats.effect.{IO, Ref}
import cats.~>
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.protocol.*
import ironmcp.server.ToolSet
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Response, Status}
import org.http4s.circe.*
import org.http4s.client.Client

class WeatherToolsSuite extends CatsEffectSuite:

  type Result[A] = Either[Throwable, A]

  private val meta = RequestMeta(protocolVersion = LatestProtocolVersion, clientCapabilities = ClientCapabilities.none)

  private def obs(pressurePa: Option[Double]) = ObservationProperties(
    timestamp = Some("2026-10-03T12:00:00Z"),
    textDescription = Some("Clear"),
    temperature = Some(Measurement(Some(21.0))),
    dewpoint = None,
    barometricPressure = Some(Measurement(pressurePa)),
    windSpeed = None,
    windGust = None,
    relativeHumidity = None,
    visibility = None
  )

  /** Canned weather.gov, in plain `Either` — no IO, no network. */
  private def canned(
      stationsLink: Option[String] = Some("stations-link"),
      history: Either[String, List[ObservationProperties]] = Right(Nil),
      fail: Boolean = false
  ): NwsOp ~> Result = new (NwsOp ~> Result):
    def apply[A](op: NwsOp[A]): Result[A] =
      if fail then Left(new RuntimeException("GET https://api.weather.gov/points failed: 503"))
      else
        op match
          case NwsOp.Point(_, _)   => Right(PointProperties(Some("forecast-link"), stationsLink))
          case NwsOp.Forecast(link) =>
            assertEquals(link, Some("forecast-link"))
            Right(ForecastProperties(List(Period(Some("Tonight"), Some(54), Some("Clear")))))
          case NwsOp.Stations(_)               => Right(List(StationProperties("KXYZ", Some("Test Field"))))
          case NwsOp.LatestObservation(_)      => Right(obs(Some(101500.0)))
          case NwsOp.ObservationHistory(_, _)  => Right(history)
          case NwsOp.ActiveAlerts(_, _, event) =>
            Right(event.filter(_ == "Flood Warning").toList.map(e => AlertProperties(Some(e), Some("Here"), None, None, None, None)))

  private def call(tools: ToolSet[Result], name: ToolName, args: (String, Json)*): CallToolResult =
    tools.call(CallToolParams(name, meta, Some(JsonObject(args*)))) match
      case Right(result: CallToolResult) => result
      case other                         => fail(s"unexpected: $other")

  private def text(result: CallToolResult): String =
    result.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString

  private val here = List("latitude" -> 39.7.asJson, "longitude" -> (-97.1).asJson)

  test("get_forecast follows the point's forecast link and renders its periods"):
    val result = call(WeatherTools(canned()), "get_forecast", here*)
    assertEquals(result.isError, Some(false))
    assertEquals(text(result), "NWS forecast\nTonight: Clear 54°F")
    assertEquals(result.structuredContent.flatMap(_.hcursor.get[List[String]]("periods").toOption), Some(List("Tonight: Clear 54°F")))

  test("get_conditions: a failed history is a missing trend, not a failed tool"):
    val result = call(WeatherTools(canned(history = Left("timeout"))), "get_conditions", here*)
    assertEquals(result.isError, Some(false))
    assert(text(result).contains("Pressure:  1015.0 hPa  (trend: insufficient data for a trend)"), text(result))

  test("get_conditions: the history becomes a trend"):
    val result = call(WeatherTools(canned(history = Right(List(obs(Some(101500.0)), obs(Some(101200.0)))))), "get_conditions", here*)
    assertEquals(result.structuredContent.flatMap(_.hcursor.get[String]("pressure_trend").toOption), Some("rising +3.0 hPa over 2 readings"))

  test("get_conditions: no stations link is a tool error the model can read"):
    val result = call(WeatherTools(canned(stationsLink = None)), "get_conditions", here*)
    assertEquals(result.isError, Some(true))
    assertEquals(text(result), "get_conditions failed: no observation-stations url for this point")

  test("get_alerts passes the event filter through"):
    val flood = call(WeatherTools(canned()), "get_alerts", (here :+ ("event" -> "Flood Warning".asJson))*)
    assertEquals(flood.structuredContent.flatMap(_.hcursor.get[Int]("count").toOption), Some(1))
    val none = call(WeatherTools(canned()), "get_alerts", here*)
    assertEquals(text(none), "No active alerts near this location.")

  test("a failing request becomes an isError result naming the tool"):
    val result = call(WeatherTools(canned(fail = true)), "get_forecast", here*)
    assertEquals(result.isError, Some(true))
    assertEquals(text(result), "get_forecast failed: GET https://api.weather.gov/points failed: 503")

  /** The keys a structured result writes are exactly the advertised properties, less any optional ones it omitted. */
  private def conforms(tools: ToolSet[Result], name: ToolName, result: CallToolResult): Unit =
    val schema = tools.list(ListToolsParams(meta)).toOption.flatMap(_.tools.find(_.name == name)).flatMap(_.outputSchema)
      .getOrElse(fail(s"$name declares no output schema"))
    val properties = schema("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)
    val required   = schema("required").flatMap(_.as[List[String]].toOption).getOrElse(Nil).toSet
    val written    = result.structuredContent.flatMap(_.asObject).map(_.keys.toSet).getOrElse(fail(s"$name wrote no structured content"))
    assert(written.subsetOf(properties), s"$name wrote ${written -- properties} beyond its schema")
    assert(required.subsetOf(written), s"$name left out required ${required -- written}")

  test("every structured result matches the output schema its tool advertises"):
    val tools = WeatherTools(canned(history = Right(List(obs(Some(101500.0)), obs(Some(101200.0))))))
    conforms(tools, "get_forecast", call(tools, "get_forecast", here*))
    conforms(tools, "get_conditions", call(tools, "get_conditions", here*))
    conforms(tools, "get_alerts", call(tools, "get_alerts", (here :+ ("event" -> "Flood Warning".asJson))*))

  test("NwsHttp4s builds weather.gov URLs and decodes GeoJSON"):
    val point = Json.obj(
      "type" -> "Feature".asJson,
      "properties" -> Json.obj("forecast" -> "https://nws.test/forecast".asJson, "observationStations" -> Json.Null, "gridId" -> "TOP".asJson)
    )
    val alerts = Json.obj("features" -> Json.arr(Json.obj("properties" -> Json.obj("event" -> "Flood Warning".asJson))))
    for
      seen <- Ref.of[IO, List[String]](Nil)
      app = HttpApp[IO] { req =>
              val target = req.uri.renderString
              seen.update(_ :+ target).as(
                if target.contains("/points/") then Response[IO](Status.Ok).withEntity(point)
                else Response[IO](Status.Ok).withEntity(alerts)
              )
            }
      nws  = NwsHttp4s(Client.fromHttpApp(app), base = "https://nws.test")
      pt  <- nws(NwsOp.Point(39.7, -97.1))
      al  <- nws(NwsOp.ActiveAlerts(39.7, -97.1, Some("Flood Warning")))
      all <- seen.get
    yield
      assertEquals(pt, PointProperties(Some("https://nws.test/forecast"), None))
      assertEquals(al.flatMap(_.event), List("Flood Warning"))
      assertEquals(all, List("https://nws.test/points/39.7,-97.1", "https://nws.test/alerts/active?point=39.7,-97.1&event=Flood+Warning"))

  test("NwsHttp4s covers Forecast, Stations, LatestObservation, ObservationHistory, and errors"):
    val forecast = Json.obj("properties" -> Json.obj("periods" -> Json.arr()))
    val stations = Json.obj("features" -> Json.arr(Json.obj("properties" -> Json.obj("stationIdentifier" -> "KORD".asJson))))
    val obs      = Json.obj("properties" -> Json.obj("textDescription" -> "Clear".asJson))
    val hist     = Json.obj("features" -> Json.arr(obs))
    val app = HttpApp[IO] { req =>
      val target = req.uri.renderString
      IO.pure(
        if target.endsWith("/forecast") || target.endsWith("/forecasts/auto") then Response[IO](Status.Ok).withEntity(forecast)
        else if target.endsWith("/stations") then Response[IO](Status.Ok).withEntity(stations)
        else if target.endsWith("/observations/latest") then Response[IO](Status.Ok).withEntity(obs)
        else if target.contains("/observations?limit=") && target.contains("/KORD/") then Response[IO](Status.Ok).withEntity(hist)
        else Response[IO](Status.InternalServerError)
      )
    }
    val nws = NwsHttp4s(Client.fromHttpApp(app), base = "https://nws.test")
    for
      fc1  <- nws(NwsOp.Forecast(Some("https://nws.test/forecast")))
      fc2  <- nws(NwsOp.Forecast(None))
      st   <- nws(NwsOp.Stations("https://nws.test/stations"))
      lo   <- nws(NwsOp.LatestObservation("KORD"))
      hOk  <- nws(NwsOp.ObservationHistory("KORD", 5))
      hErr <- nws(NwsOp.ObservationHistory("KBAD", 5))
      fail <- nws(NwsOp.Point(0.0, 0.0)).attempt
    yield
      assertEquals(fc1.periods, Nil)
      assertEquals(fc2.periods, Nil)
      assertEquals(st.map(_.stationIdentifier), List("KORD"))
      assertEquals(lo.textDescription, Some("Clear"))
      assertEquals(hOk.map(_.flatMap(_.textDescription)), Right(List("Clear")))
      assert(hErr.isLeft)
      assert(fail.left.exists(_.getMessage.contains("GET https://nws.test/points/0.0,0.0 failed:")))

  test("conditions with empty stations and steady pressureTrend"):
    type ErrOr[A] = Either[Throwable, A]
    val emptyStations: NwsOp ~> ErrOr = new (NwsOp ~> ErrOr):
      def apply[A](op: NwsOp[A]): ErrOr[A] = op match
        case NwsOp.Point(_, _) => Right(PointProperties(None, Some("https://nws.test/stations")))
        case NwsOp.Stations(_) => Right(Nil)
        case other             => Left(new RuntimeException(s"unexpected $other"))
    val res = WeatherTools.conditions[NwsOp](GetConditions(39.7, -97.1)).foldMap(emptyStations)
    assertEquals(res, Right(Left("get_conditions failed: no observing station found near this point")))
    assertEquals(WeatherTools.pressureTrend(List(1013.2, 1013.4)), "steady (~2 readings)")
    val _ = Nws[NwsOp]
