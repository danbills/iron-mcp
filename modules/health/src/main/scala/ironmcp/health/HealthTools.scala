package ironmcp
package health

import cats.MonadThrow
import cats.free.Free
import cats.~>
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.protocol.*
import ironmcp.server.*

object HealthTools:

  val DashboardUri: UiUri = "ui://health/dashboard.html"

  def health: Free[HealthOp, Health] =
    for
      services <- HealthOp.services
      now      <- HealthOp.now
    yield Health(services, now)

  /** `get_system_health` is for the model and the View; `refresh_health` is
    * the View's refresh button, kept from the model by its visibility.
    */
  def apply[F[_]: MonadThrow](backend: HealthOp ~> F): ToolSet[F] =
    val readOnly = Some(ToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(false)))
    ToolSet.of(
      McpTool[NoArgs](
        name = "get_system_health",
        description = "Returns current health status for all services.",
        title = Some("System Health"),
        annotations = readOnly
      )(_ => health.foldMap(backend)).withUi(DashboardUri),
      McpTool[NoArgs](
        name = "refresh_health",
        description = "Fetches latest health data for the dashboard.",
        title = Some("Refresh Health Data"),
        annotations = readOnly
      )(_ => health.foldMap(backend)).withUi(DashboardUri, Visibility.App)
    )

  /** The View, read from the classpath: one self-contained HTML document. */
  def dashboard(html: String): UiResource =
    UiResource(DashboardUri, "Health Dashboard", html, Some("Interactive service health dashboard"), UiResourceMeta(prefersBorder = Some(true)))
