package ironmcp
package health

import cats.Show
import cats.free.Free
import io.circe.Encoder
import io.circe.derivation.{Configuration, ConfiguredEnumCodec}
import ironmcp.schema.{ConfiguredEnumJsonSchemaOf, JsonSchemaOf}

// --- the dashboard's data ------------------------------------------------------

given Configuration = Configuration.default

enum Status derives ConfiguredEnumCodec, ConfiguredEnumJsonSchemaOf:
  case healthy, degraded, down

final case class ServiceHealth(
    name: String,
    status: Status,
    uptimePct: Double,
    cpuPct: Double,
    memoryMb: Int,
    responseTimeMs: Double
) derives Encoder.AsObject, JsonSchemaOf

/** What both tools return. The model reads the one-line summary in the text
  * block; the View renders `structuredContent`, which is this, encoded.
  */
final case class Health(services: List[ServiceHealth], timestamp: Long) derives Encoder.AsObject, JsonSchemaOf

object Health:
  given Show[Health] = h => s"${h.services.count(_.status == Status.healthy)}/${h.services.size} services healthy."

/** Neither tool takes arguments. */
final case class NoArgs() derives io.circe.Decoder, JsonSchemaOf

// --- the algebra the tools are written in ---------------------------------------

/** What the tools ask of the monitoring backend. [[SimulatedHealth]]
  * interprets it with made-up, jittering numbers, as the handbook's sample does.
  */
enum HealthOp[A]:
  case Services extends HealthOp[List[ServiceHealth]]
  case Now      extends HealthOp[Long]

object HealthOp:
  val services: Free[HealthOp, List[ServiceHealth]] = Free.liftF(HealthOp.Services)
  val now: Free[HealthOp, Long]                     = Free.liftF(HealthOp.Now)
