package ironmcp
package health

import cats.Monad
import cats.effect.Clock
import cats.effect.std.Random
import cats.syntax.all.*
import cats.~>

/** Fabricated health data with jitter on every call, so a refresh visibly
  * changes the dashboard: the handbook sample's `getHealthData`.
  */
object SimulatedHealth:

  private val catalogue = List("api-gateway" -> 12.0, "auth-service" -> 8.0, "billing-service" -> 45.0, "search-index" -> 30.0)

  def apply[F[_]: {Monad, Clock}](random: Random[F]): HealthOp ~> F = new (HealthOp ~> F):
    def apply[A](op: HealthOp[A]): F[A] = op match
      case HealthOp.Now      => Clock[F].realTime.map(_.toMillis)
      case HealthOp.Services => catalogue.traverse((name, base) => service(name, base))

    private def jitter(base: Double, spread: Double): F[Double] =
      random.nextDouble.map(r => math.round((base + (r - 0.5) * spread) * 10) / 10.0)

    private def service(name: String, base: Double): F[ServiceHealth] =
      for
        roll    <- random.nextDouble
        status   = if roll < 0.75 then Status.healthy else if roll < 0.95 then Status.degraded else Status.down
        uptime  <- if status == Status.down then jitter(82, 4) else jitter(99.9, 0.2)
        cpu     <- jitter(if status == Status.degraded then 78 else 24, 12)
        memory  <- jitter(512, 128)
        latency <- if status == Status.healthy then jitter(base, 6) else jitter(base * 8, 40)
      yield ServiceHealth(name, status, uptime, cpu, memory.round.toInt, latency)
