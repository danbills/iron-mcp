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
