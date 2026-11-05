package ironmcp
package schema

import io.github.iltotore.iron.Constraint

/** Schema annotations written as Iron constraints, so they sit in the type next
  * to the real constraints and the schema macro reads them the same way.
  *
  * Neither restricts a value: both always pass. They only add keywords.
  *
  * {{{
  * type Duration = String :| ((Titled["Duration (minutes)"] & Default["30"]) DescribedAs "How long to hold the room.")
  * }}}
  */

/** Adds `"title": T`. Elicitation forms use it as the field label. */
final class Titled[T <: String]

object Titled:
  given [A, T <: String]: Constraint[A, Titled[T]] with
    override inline def test(inline value: A): Boolean = true
    override inline def message: String                = "Always valid"

/** Adds `"default": V`. A hint for whoever fills the value in (a form, a
  * model); decoding does not substitute it, so a field that may be omitted
  * should still be an `Option`.
  */
final class Default[V]

object Default:
  given [A, V]: Constraint[A, Default[V]] with
    override inline def test(inline value: A): Boolean = true
    override inline def message: String                = "Always valid"
