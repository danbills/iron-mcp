package ironmcp
package eventlog

/** How events of type `E` change a state of type `S`: where the state starts, and one event applied to it. Applying
  * an event must be pure and read no clock (an event carries its own time), so that replaying a journal gives the
  * same state the live log had.
  */
trait Evolve[S, E]:
  def initial: S
  def apply(state: S, event: E): S

object Evolve:
  def apply[S, E](using evolve: Evolve[S, E]): Evolve[S, E] = evolve

  /** The state after `events`, from `state`. */
  def replay[S, E](state: S, events: Iterable[E])(using evolve: Evolve[S, E]): S =
    events.foldLeft(state)(evolve.apply)
