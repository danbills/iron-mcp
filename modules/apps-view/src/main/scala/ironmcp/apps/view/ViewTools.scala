package ironmcp.apps.view

import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.JsonObject
import ironmcp.protocol.{CallToolResult, Tool}

/** What answers the host's `tools/list` and `tools/call` for a View. [[ViewTools]]
  * is the usual one; implement this directly to answer every call yourself, as
  * ext-apps' `oncalltool` / `onlisttools` do.
  */
trait ViewToolProvider[F[_]]:

  /** The tools the host may call now. */
  def list: F[List[Tool]]

  /** Calls one. `None` when the View has no such tool now: the host gets `-32602`. */
  def call(name: String, arguments: JsonObject): F[Option[CallToolResult]]

  /** One element each time [[list]] changes, after the first: the View tells
    * the host (`notifications/tools/list_changed`).
    */
  def changes: Stream[F, Unit]

/** A View's tools, changeable while it runs, as ext-apps' registered app
  * tools: add, remove, enable, disable. Each change tells the host.
  */
final class ViewTools[F[_]] private (entries: SignallingRef[F, Vector[ViewTools.Entry[F]]])(using F: Concurrent[F])
    extends ViewToolProvider[F]:
  import ViewTools.Entry

  /** Adds a tool, enabled, replacing one of the same name. */
  def add(tool: ViewTool[F]): F[Unit] =
    entries.update(all => all.filterNot(_.tool.name == tool.name) :+ Entry(tool, enabled = true))

  def remove(name: String): F[Unit] = entries.update(_.filterNot(_.tool.name == name))

  /** A disabled tool stays registered but is neither listed nor callable. */
  def enable(name: String): F[Unit]  = enabled(name, value = true)
  def disable(name: String): F[Unit] = enabled(name, value = false)

  def list: F[List[Tool]] = entries.get.map(_.collect { case Entry(tool, true) => tool.definition }.toList)

  def call(name: String, arguments: JsonObject): F[Option[CallToolResult]] =
    entries.get.flatMap(_.collectFirst { case Entry(tool, true) if tool.name == name => tool }.traverse(_.call(arguments)))

  def changes: Stream[F, Unit] = entries.discrete.drop(1).void

  private def enabled(name: String, value: Boolean): F[Unit] =
    entries.update(_.map(entry => if entry.tool.name == name then entry.copy(enabled = value) else entry))

object ViewTools:
  final case class Entry[F[_]](tool: ViewTool[F], enabled: Boolean)

  def of[F[_]: Concurrent](tools: ViewTool[F]*): F[ViewTools[F]] =
    SignallingRef.of[F, Vector[Entry[F]]](tools.toVector.map(Entry(_, enabled = true))).map(ViewTools(_))
