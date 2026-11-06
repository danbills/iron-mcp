package ironmcp.apps.view

import cats.effect.syntax.all.*
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all.*
import org.scalajs.dom

/** Pads an element by the host's `safeAreaInsets` (a phone's notch, a host's
  * overlay), as ext-apps' examples do, so content stays clear of them.
  */
object SafeArea:

  def apply[F[_]](context: HostContext, element: dom.HTMLElement)(using F: Sync[F]): F[Unit] =
    F.delay(context.safeAreaInsets.foreach { insets =>
      element.style.paddingTop = s"${px(insets.top)}px"
      element.style.paddingRight = s"${px(insets.right)}px"
      element.style.paddingBottom = s"${px(insets.bottom)}px"
      element.style.paddingLeft = s"${px(insets.left)}px"
    })

  /** Applies the insets now and whenever the host context changes, until released. */
  def follow[F[_]: Async](view: McpAppView[F], element: dom.HTMLElement = dom.document.body): Resource[F, Unit] =
    view.hostContext.discrete.evalMap(apply[F](_, element)).compile.drain.background.void

  /** `12` rather than `12.0`, as JavaScript writes a whole number. */
  private def px(value: Double): String = if value.isWhole then value.toLong.toString else value.toString
