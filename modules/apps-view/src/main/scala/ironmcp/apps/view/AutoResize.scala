package ironmcp.apps.view

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import org.scalajs.dom

import scala.scalajs.js

/** Keeps the host told of the View's size (`ui/notifications/size-changed`),
  * so it can fit the iframe to the content. It measures as ext-apps' `App`
  * does with `autoResize` (on there by default), so a host sizes a Scala.js
  * View exactly as it sizes a TypeScript one:
  *
  *  - at most once per animation frame, whenever `<html>` or `<body>` resizes;
  *  - height: `<html>`'s with its height set to `max-content` for the
  *    measurement, so content taller than the iframe is measured whole;
  *  - width: the window's inner width;
  *  - sent only when either changed, and once at the start.
  *
  * Release the resource to stop (ext-apps' cleanup function).
  */
object AutoResize:

  def apply[F[_]](view: McpAppView[F])(using F: Async[F]): Resource[F, Unit] =
    Dispatcher.sequential[F].flatMap { dispatcher =>
      Resource
        .make(F.delay {
          val html      = dom.document.documentElement.asInstanceOf[dom.HTMLElement]
          var scheduled = false
          var last      = (0, 0)

          def measure(): Unit =
            if !scheduled then
              scheduled = true
              dom.window.requestAnimationFrame { (_: Double) =>
                scheduled = false
                val original = html.style.height
                html.style.height = "max-content"
                val height = math.ceil(html.getBoundingClientRect().height).toInt
                html.style.height = original
                val width = math.ceil(dom.window.innerWidth.toDouble).toInt
                if (width, height) != last then
                  last = (width, height)
                  dispatcher.unsafeRunAndForget(view.sendSizeChanged(Some(width), Some(height)))
              }
              ()

          measure()
          val observer = new dom.ResizeObserver((_: js.Array[dom.ResizeObserverEntry], _: dom.ResizeObserver) => measure())
          observer.observe(html)
          observer.observe(dom.document.body)
          observer
        })(observer => F.delay(observer.disconnect()))
        .void
    }
