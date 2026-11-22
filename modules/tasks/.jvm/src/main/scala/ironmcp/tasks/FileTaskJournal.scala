package ironmcp
package tasks

import cats.effect.{Resource, Sync}
import ironmcp.eventlog.FileEventJournal

import java.nio.file.Path

/** A [[TaskJournal]] in a directory: a [[FileEventJournal]] of [[TaskEvent]]s, with snapshots of the [[TaskState]].
  *
  * {{{
  * snapshot-00000000000000004096.json   {"seq":4096,"state":{...}}      the state after event 4096
  * events-00000000000000004097.jsonl    {"seq":4097,"event":{...}} ...  events from 4097 on
  * }}}
  *
  * A last line cut short by a crash is dropped; anything else unreadable fails the open rather than lose tasks
  * quietly. Every `snapshotEvery` events, and on release, the state is snapshotted and the files it covers go.
  * With `fsync`, each batch is on disk before it is answered. Owner-only where the file system has POSIX
  * permissions: the files hold tool arguments and results.
  */
object FileTaskJournal:

  def open[F[_]: Sync](dir: Path, snapshotEvery: Int = 4096, fsync: Boolean = true): Resource[F, TaskJournal[F]] =
    FileEventJournal.open[F, TaskEvent, TaskState](dir, snapshotEvery, fsync)
