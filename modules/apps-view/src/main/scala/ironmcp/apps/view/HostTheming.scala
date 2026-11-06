package ironmcp.apps.view

import cats.effect.syntax.all.*
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all.*
import org.scalajs.dom

/** Puts the host's look on the page, as ext-apps' `applyDocumentTheme`,
  * `applyHostStyleVariables` and `applyHostFonts` do:
  *
  *  - the theme as `data-theme` and `color-scheme` on `<html>`, so CSS can
  *    switch on `:root[data-theme="dark"]` and form controls follow;
  *  - the host's CSS variables (`--color-background-primary`, ...) on `<html>`;
  *  - the host's font CSS in a `<style id="mcp-host-fonts">`.
  *
  * The page's own CSS should fall back when a variable is missing:
  * `var(--color-text-primary, #1f2328)`.
  */
object HostTheming:

  def apply[F[_]](context: HostContext)(using F: Sync[F]): F[Unit] = F.delay {
    val root = dom.document.documentElement.asInstanceOf[dom.HTMLElement]
    context.theme.foreach { theme =>
      root.setAttribute("data-theme", theme.toString)
      root.style.setProperty("color-scheme", theme.toString)
    }
    context.styles.foreach { styles =>
      styles.variables.foreach((name, value) => root.style.setProperty(name, value))
      styles.fonts.foreach { css =>
        val style = Option(dom.document.getElementById(FontsId)).getOrElse {
          val created = dom.document.createElement("style")
          created.id = FontsId
          dom.document.head.appendChild(created)
          created
        }
        style.textContent = css
      }
    }
  }

  /** Applies the host context now and whenever it changes, until released. */
  def follow[F[_]: Async](view: McpAppView[F]): Resource[F, Unit] =
    view.hostContext.discrete.evalMap(apply[F]).compile.drain.background.void

  private val FontsId = "mcp-host-fonts"
