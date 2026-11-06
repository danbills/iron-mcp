package ironmcp.apps.view

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import org.scalajs.dom

import scala.scalajs.js

/** Keeps the host told of the View's size: a `ResizeObserver` on `element`
  * sends `ui/notifications/size-changed` whenever its rendered size changes,
  * so the host can fit the iframe to the content.
  *
  * Only the height is sent unless `width` is set: an inline View usually
  * takes the width the host gives it.
  */
object AutoResize:

  def apply[F[_]](
      view: McpAppView[F],
      element: dom.Element = dom.document.documentElement,
      width: Boolean = false
  )(using F: Async[F]): Resource[F, Unit] =
    Dispatcher.sequential[F].flatMap { dispatcher =>
      Resource
        .make(F.delay {
          var last = (-1, -1)
          val observer = new dom.ResizeObserver((_: js.Array[dom.ResizeObserverEntry], _: dom.ResizeObserver) => {
            val box  = element.getBoundingClientRect()
            val size = (math.ceil(box.width).toInt, math.ceil(box.height).toInt)
            if size != last then
              last = size
              dispatcher.unsafeRunAndForget(view.sendSizeChanged(Option.when(width)(size._1), Some(size._2)))
          })
          observer.observe(element)
          observer
        })(observer => F.delay(observer.disconnect()))
        .void
    }
