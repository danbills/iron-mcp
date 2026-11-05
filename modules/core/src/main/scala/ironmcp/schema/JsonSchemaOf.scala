package ironmcp
package schema

import io.circe.{Json, JsonObject}
import io.circe.derivation.Configuration
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

  /** An opaque `RefinedType` (`object Port extends RefinedType[Int, Interval.Closed[1, 65535]]`) has the schema
    * of the refined type it wraps, so nominal types cost nothing in the advertised schema.
    */
  given refinedType[T](using mirror: RefinedType.Mirror[T], base: JsonSchemaOf[mirror.IronType]): JsonSchemaOf[T] =
    instance(base.schema)

  /** Derivation from the Mirror.
    *
    * A case class is an object: field names from the Mirror, field schemas
    * from their own instances, `required` from which fields are `Option`.
    *
    * An enum or sealed trait is one of its cases, each an object with a single
    * property named after the case — circe's default (`derives Codec`)
    * encoding of a sum. For a discriminator field or plain strings, derive
    * [[ConfiguredJsonSchemaOf]] or [[ConfiguredEnumJsonSchemaOf]] alongside
    * the matching circe codec.
    */
  inline given derived[A](using mirror: Mirror.Of[A]): JsonSchemaOf[A] =
    inline mirror match
      case p: Mirror.ProductOf[A] => instance(product[A](identity)(using p))
      case s: Mirror.SumOf[A]     => instance(tagged[A](identity, identity)(using s))

  /** The schema as OpenAI's strict structured outputs accept it.
    *
    * Strict mode wants every object closed (`additionalProperties: false`)
    * and every property listed in `required`, so a field that may be left out
    * becomes one that may be `null` instead — which circe decodes to `None`
    * all the same. `oneOf` becomes `anyOf`: the cases of a derived sum are
    * told apart by their tag, so the two mean the same here.
    *
    * Objects without `properties` (maps) are left as they are; strict mode
    * does not accept them, and no rewrite would make them acceptable.
    */
  def strict(schema: Json): Json =
    schema.asObject.fold(schema)(obj => Json.fromJsonObject(strictObject(obj)))

  private def strictObject(obj: JsonObject): JsonObject =
    val inner = JsonObject.fromIterable(obj.toList.map {
      case ("properties", props)                        => "properties" -> props.mapObject(_.mapValues(strict))
      case ("items", items)                             => "items" -> strict(items)
      case ("additionalProperties", s) if s.isObject    => "additionalProperties" -> strict(s)
      case ("oneOf", cases)                             => "anyOf" -> cases.mapArray(_.map(strict))
      case (key @ ("anyOf" | "allOf"), cases)           => key -> cases.mapArray(_.map(strict))
      case other                                        => other
    })
    inner("properties").flatMap(_.asObject) match
      case None => inner
      case Some(props) =>
        val required = inner("required").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).toSet
        val closed   = props.toList.map((name, schema) => name -> (if required(name) then schema else nullable(schema)))
        inner
          .add("properties", Json.fromJsonObject(JsonObject.fromIterable(closed)))
          .add("required", Json.arr(props.keys.map(Json.fromString).toSeq*))
          .add("additionalProperties", Json.False)

  private def nullable(schema: Json): Json =
    Json.obj("anyOf" -> Json.arr(schema, Json.obj("type" -> Json.fromString("null"))))

  /** The object schema of a case class whose property names are its field names passed through `rename`. */
  private[schema] inline def product[A](rename: String => String)(using mirror: Mirror.ProductOf[A]): Json =
    val labels   = constValueTuple[mirror.MirroredElemLabels].toList.map(label => rename(label.toString))
    val elements = summonAll[Tuple.Map[mirror.MirroredElemTypes, JsonSchemaOf]].toList
      .map(_.asInstanceOf[JsonSchemaOf[?]])
    val fields = labels.zip(elements)
    Json.obj(
      "type"       -> Json.fromString("object"),
      "properties" -> Json.obj(fields.map((label, element) => label -> element.schema)*),
      "required"   -> Json.arr(fields.collect { case (label, e) if e.required => Json.fromString(label) }*)
    )

  /** A sum as circe's derived codec writes it with no discriminator: `{"Case": {...fields}}`. */
  private[schema] inline def tagged[A](renameCase: String => String, renameField: String => String)(using
      mirror: Mirror.SumOf[A]
  ): Json =
    val labels = constValueTuple[mirror.MirroredElemLabels].toList.map(label => renameCase(label.toString))
    val cases  = labels.zip(caseSchemas[mirror.MirroredElemTypes](renameField)).map((label, body) =>
      Json.obj(
        "type"                 -> Json.fromString("object"),
        "properties"           -> Json.obj(label -> body),
        "required"             -> Json.arr(Json.fromString(label)),
        "additionalProperties" -> Json.False
      )
    )
    Json.obj("oneOf" -> Json.arr(cases*))

  /** A sum as circe's derived codec writes it with a discriminator: the case's fields plus `key: "Case"`. */
  private[schema] inline def discriminated[A](key: String, renameCase: String => String, renameField: String => String)(
      using mirror: Mirror.SumOf[A]
  ): Json =
    val labels = constValueTuple[mirror.MirroredElemLabels].toList.map(label => renameCase(label.toString))
    val cases  = labels.zip(caseSchemas[mirror.MirroredElemTypes](renameField)).map((label, body) =>
      val tag = Json.obj("type" -> Json.fromString("string"), "const" -> Json.fromString(label))
      body.mapObject(obj =>
        obj
          .add("properties", Json.obj(key -> tag).deepMerge(obj("properties").getOrElse(Json.obj())))
          .add("required", Json.arr(Json.fromString(key) +: obj("required").flatMap(_.asArray).getOrElse(Vector.empty)*))
      )
    )
    Json.obj("oneOf" -> Json.arr(cases*))

  /** The object schema of each case, in declaration order. Cases must be case classes or enum cases. */
  private[schema] inline def caseSchemas[Cases <: Tuple](rename: String => String): List[Json] =
    inline erasedValue[Cases] match
      case _: EmptyTuple => Nil
      case _: (c *: cs)  => product[c](rename)(using summonInline[Mirror.ProductOf[c]]) :: caseSchemas[cs](rename)

  /** The case names of a sum whose cases all have no fields, or a compile error naming the type. */
  private[schema] inline def singletonLabels[A](using mirror: Mirror.SumOf[A]): List[String] =
    allSingletons[mirror.MirroredElemTypes]
    constValueTuple[mirror.MirroredElemLabels].toList.map(_.toString)

  private inline def allSingletons[Cases <: Tuple]: Unit =
    inline erasedValue[Cases] match
      case _: EmptyTuple => ()
      case _: (c *: cs) =>
        fieldless[c]
        allSingletons[cs]

  private inline def fieldless[C]: Unit =
    summonFrom { case m: Mirror.ProductOf[C] =>
      inline erasedValue[m.MirroredElemTypes] match
        case _: EmptyTuple => ()
        case _             => error("ConfiguredEnumJsonSchemaOf needs every case to have no fields")
    }

/** [[JsonSchemaOf]] for a case class whose circe codec is derived with a `Configuration`
  * (`derives ConfiguredEncoder, ConfiguredJsonSchemaOf`): property names go through the
  * same `transformMemberNames`, so the schema names the keys the encoder writes.
  */
trait ConfiguredJsonSchemaOf[A] extends JsonSchemaOf[A]

object ConfiguredJsonSchemaOf:
  /** A case class, or a sum: tagged by `discriminator` when the configuration has one, otherwise wrapped in an
    * object keyed by the (renamed) case name — whichever the configured circe codec writes.
    */
  inline def derived[A](using configuration: Configuration, mirror: Mirror.Of[A]): ConfiguredJsonSchemaOf[A] =
    inline mirror match
      case p: Mirror.ProductOf[A] => instance(JsonSchemaOf.product[A](configuration.transformMemberNames)(using p))
      case s: Mirror.SumOf[A] =>
        instance(configuration.discriminator match
          case Some(key) =>
            JsonSchemaOf.discriminated[A](key, configuration.transformConstructorNames,
              configuration.transformMemberNames)(using s)
          case None =>
            JsonSchemaOf.tagged[A](configuration.transformConstructorNames, configuration.transformMemberNames)(using s)
        )

  def instance[A](json: Json): ConfiguredJsonSchemaOf[A] = new ConfiguredJsonSchemaOf[A]:
    val schema: Json = json

/** [[JsonSchemaOf]] for an enum whose cases have no fields, encoded as plain strings by circe's
  * `ConfiguredEnumCodec` (`derives ConfiguredEnumCodec, ConfiguredEnumJsonSchemaOf`):
  * `{"type": "string", "enum": [...]}`, names passed through `transformConstructorNames`.
  * A case with fields is a compile error.
  */
trait ConfiguredEnumJsonSchemaOf[A] extends JsonSchemaOf[A]

object ConfiguredEnumJsonSchemaOf:
  inline def derived[A](using configuration: Configuration, mirror: Mirror.SumOf[A]): ConfiguredEnumJsonSchemaOf[A] =
    instance(JsonSchemaOf.singletonLabels[A].map(configuration.transformConstructorNames))

  def instance[A](names: List[String]): ConfiguredEnumJsonSchemaOf[A] = new ConfiguredEnumJsonSchemaOf[A]:
    val schema: Json = Json.obj(
      "type" -> Json.fromString("string"),
      "enum" -> Json.arr(names.map(Json.fromString)*)
    )
