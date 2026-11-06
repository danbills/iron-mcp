package ironmcp
package schema

import io.circe.{Json, parser}

/** The JSON Schema keywords an Iron constraint implies.
  *
  * The instance is produced by a macro that walks the constraint type, so
  * arbitrary compositions work — `Interval.Closed[1, 10]` expands to
  * `DescribedAs[GreaterEqual[1] & LessEqual[10], _]` and yields
  * `{"minimum": 1, "maximum": 10}` without anyone writing that down.
  *
  * A constraint with no schema reading contributes no keywords rather than
  * failing the build: an exotic predicate should not stop a tool being served.
  * It is still enforced — the decoder rejects bad values whatever the
  * advertised schema says.
  */
trait ConstraintSchema[C]:
  def keywords: Json

object ConstraintSchema:

  def fromJson[C](raw: String): ConstraintSchema[C] = new ConstraintSchema[C]:
    val keywords: Json = parser.parse(raw).getOrElse(Json.obj())

  inline given derived[C]: ConstraintSchema[C] = ${ ConstraintSchemaMacros.instanceOf[C] }
