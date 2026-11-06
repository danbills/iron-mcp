package ironmcp
package health

import cats.effect.IO
import cats.effect.std.Random
import cats.~>
import io.circe.Json
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.CatsEffectSuite

class HealthToolsSuite extends CatsEffectSuite:

  type Result[A] = Either[Throwable, A]

  private val services = List(
    ServiceHealth("api-gateway", Status.healthy, 99.9, 20.0, 500, 12.0),
    ServiceHealth("billing-service", Status.degraded, 99.8, 80.0, 600, 300.0)
  )

  /** A canned backend, in plain `Either`: no IO, no randomness. */
  private val canned: HealthOp ~> Result = new (HealthOp ~> Result):
    def apply[A](op: HealthOp[A]): Result[A] = op match
      case HealthOp.Services => Right(services)
      case HealthOp.Now      => Right(1_700_000_000_000L)

  private val meta = RequestMeta(protocolVersion = LatestProtocolVersion, clientCapabilities = ClientCapabilities.none)

  private def call(name: ToolName): CallToolResult =
    HealthTools(canned).call(CallToolParams(name, meta, Some(io.circe.JsonObject.empty))) match
      case Right(r: CallToolResult) => r
      case other                    => fail(s"unexpected $other")

  test("get_system_health: a one-line summary for the model, the services for the View"):
    val result = call("get_system_health")
    assertEquals(result.content, List(ContentBlock.Text("1/2 services healthy.")))
    val data = result.structuredContent.get
    assertEquals(data.hcursor.get[Long]("timestamp"), Right(1_700_000_000_000L))
    assertEquals(data.hcursor.downField("services").downArray.get[String]("status"), Right("healthy"))

  test("both tools name the dashboard; refresh_health is for the View only"):
    val tools = HealthTools(canned).list(ListToolsParams(meta)).toOption.get.tools
    val ui    = tools.map(t => t.name -> t._meta.flatMap(_.get("ui")).getOrElse(Json.Null)).toMap
    assertEquals(ui("get_system_health").hcursor.get[String]("resourceUri"), Right("ui://health/dashboard.html"))
    assertEquals(ui("refresh_health").hcursor.get[List[String]]("visibility"), Right(List("app")))
    assertEquals(tools.head.outputSchema.flatMap(_("properties")).flatMap(_.hcursor.downField("services").downField("items").downField("properties").downField("status").get[List[String]]("enum").toOption), Some(List("healthy", "degraded", "down")))

  test("the simulated backend gives four services with plausible numbers"):
    Random.scalaUtilRandom[IO].flatMap(random => HealthTools.health.foldMap(SimulatedHealth[IO](random))).map { health =>
      assertEquals(health.services.map(_.name), List("api-gateway", "auth-service", "billing-service", "search-index"))
      assert(health.services.forall(s => s.uptimePct > 70 && s.uptimePct <= 100.1), health.asJson.noSpaces)
    }

  test("the View ships on the classpath, self-contained and speaking the Apps protocol"):
    val html = scala.io.Source.fromResource("ironmcp/health/dashboard.html")(using scala.io.Codec.UTF8).mkString
    assert(html.contains("ui/initialize") && html.contains("ui/notifications/tool-result") && html.contains("refresh_health"))
    assert(!html.contains("<script src"), "no external scripts: the default sandbox would block them")
