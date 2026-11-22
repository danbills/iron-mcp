package ironmcp
package eventlog

/** Where a log's events go to last: [[EventLog.journaled]] writes each batch here before answering. */
trait EventJournal[F[_], E, S]:

  /** The state the journal held when it was opened: its latest snapshot, with every later event applied. */
  def recovered: S

  /** Writes `events` durably, in order. `after` is the state they lead to, which the journal may snapshot. */
  def append(events: List[E], after: S): F[Unit]

/** A journal could not write, so the log stopped taking changes: what is in memory and what is on disk would
  * otherwise part ways. Restart the process to carry on from the journal.
  */
final case class JournalFailed(cause: Throwable)
    extends RuntimeException(s"the journal failed, so the log takes no more changes: ${cause.getMessage}", cause)
