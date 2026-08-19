package ironmcp
package schema

import io.circe.Json
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.any.{DescribedAs, Not, StrictEqual}
import io.github.iltotore.iron.constraint.collection.Length
import io.github.iltotore.iron.constraint.numeric.{Greater, Less}
import io.github.iltotore.iron.constraint.string.Match

import scala.compiletime.*
import scala.deriving.Mirror

/** JSON Schema derived from the Scala type, including its Iron constraints.
  *
  * The constraint is already the specification of what a value may be, so
  * writing the schema out by hand duplicates it — and the copy drifts. Here the
  * schema is a *consequence* of the type: widen `Interval.Closed[1, 10]` and
  * the advertised `maximum` widens with it, in the same edit.
  *
  * Everything is resolved at compile time. No reflection, so this survives
  * Scala Native and GraalVM.
  */
trait JsonSchemaOf[A]:
  def schema: Json

  /** Only `Option` makes a field optional; everything else is required. */
  def required: Boolean = true

object JsonSchemaOf:

  def instance[A](json: Json): JsonSchemaOf[A] = new JsonSchemaOf[A]:
    val schema: Json = json

  given JsonSchemaOf[String]     = instance(typed("string"))
  given JsonSchemaOf[Int]        = instance(typed("integer"))
  given JsonSchemaOf[Long]       = instance(typed("integer"))
  given JsonSchemaOf[Short]      = instance(typed("integer"))
  given JsonSchemaOf[Byte]       = instance(typed("integer"))
  given JsonSchemaOf[Double]     = instance(typed("number"))
  given JsonSchemaOf[Float]      = instance(typed("number"))
  given JsonSchemaOf[BigDecimal] = instance(typed("number"))
  given JsonSchemaOf[Boolean]    = instance(typed("boolean"))
  given JsonSchemaOf[Json]       = instance(Json.obj())

  private def typed(name: String): Json = Json.obj("type" -> Json.fromString(name))

  /** An optional field is absent from `required`, and its schema is the
    * schema of what it wraps — not a union with null.
    */
  given optional[A](using inner: JsonSchemaOf[A]): JsonSchemaOf[Option[A]] =
    new JsonSchemaOf[Option[A]]:
      val schema: Json               = inner.schema
      override val required: Boolean = false

  given list[A](using inner: JsonSchemaOf[A]): JsonSchemaOf[List[A]] =
    instance(Json.obj("type" -> Json.fromString("array"), "items" -> inner.schema))

  given map[A](using inner: JsonSchemaOf[A]): JsonSchemaOf[Map[String, A]] =
    instance(Json.obj("type" -> Json.fromString("object"), "additionalProperties" -> inner.schema))

  /** A refined type is its base type plus whatever keywords the constraint
    * contributes.
    */
  given refined[A, C](using base: JsonSchemaOf[A], constraint: ConstraintSchema[C]): JsonSchemaOf[A :| C] =
    instance(base.schema.deepMerge(constraint.keywords))

  /** Derivation for case classes: field names from the Mirror, field schemas
    * from their own instances, `required` from which fields are `Option`.
    */
  inline given derived[A](using mirror: Mirror.ProductOf[A]): JsonSchemaOf[A] =
    val labels   = constValueTuple[mirror.MirroredElemLabels].toList.map(_.toString)
    val elements = summonAll[Tuple.Map[mirror.MirroredElemTypes, JsonSchemaOf]].toList
      .map(_.asInstanceOf[JsonSchemaOf[?]])
    val fields = labels.zip(elements)
    instance(
      Json.obj(
        "type"       -> Json.fromString("object"),
        "properties" -> Json.obj(fields.map((label, element) => label -> element.schema)*),
        "required"   -> Json.arr(fields.collect { case (label, e) if e.required => Json.fromString(label) }*)
      )
    )
