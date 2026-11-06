package ironmcp

import io.circe.{Codec, Decoder, Json}
import io.circe.derivation.{Configuration, ConfiguredCodec, ConfiguredEnumCodec}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.circe.given
import ironmcp.schema.{ConfiguredEnumJsonSchemaOf, ConfiguredJsonSchemaOf, JsonSchemaOf}
import munit.FunSuite

/** Sum types: each schema must describe exactly what the matching circe codec writes. */
class SumSchemaSuite extends FunSuite:

  enum Shape derives Codec.AsObject, JsonSchemaOf:
    case Circle(radius: Double :| Positive)
    case Square(side: Double :| Positive, label: Option[String])

  test("a derived sum is circe's default encoding: one object per case, keyed by the case name"):
    val circle: Shape = Shape.Circle(2.0)
    assertEquals(circle.asJson, Json.obj("Circle" -> Json.obj("radius" -> Json.fromDoubleOrNull(2.0))))

    val cases = summon[JsonSchemaOf[Shape]].schema.hcursor.downField("oneOf")
    assertEquals(cases.downN(0).downField("required").as[List[String]], Right(List("Circle")))
    assertEquals(cases.downN(0).get[Boolean]("additionalProperties"), Right(false))
    val radius = cases.downN(0).downField("properties").downField("Circle").downField("properties").downField("radius")
    assertEquals(radius.get[String]("type"), Right("number"))
    assertEquals(radius.get[Int]("exclusiveMinimum"), Right(0))
    val square = cases.downN(1).downField("properties").downField("Square")
    assertEquals(square.get[List[String]]("required"), Right(List("side")))

  object Discriminated:
    given Configuration = Configuration.default.withDiscriminator("kind").withSnakeCaseMemberNames

    enum Event derives ConfiguredCodec, ConfiguredJsonSchemaOf:
      case Started(jobId: String)
      case Finished(jobId: String, exitCode: Int)

  test("a configured discriminator becomes a const property on each case, with renamed fields"):
    import Discriminated.*
    val finished: Event = Event.Finished("a", 0)
    assertEquals(
      finished.asJson,
      Json.obj("job_id" -> Json.fromString("a"), "exit_code" -> Json.fromInt(0), "kind" -> Json.fromString("Finished"))
    )

    val second = summon[JsonSchemaOf[Event]].schema.hcursor.downField("oneOf").downN(1)
    assertEquals(second.downField("properties").downField("kind").get[String]("const"), Right("Finished"))
    assertEquals(second.get[List[String]]("required"), Right(List("kind", "job_id", "exit_code")))

  object Strings:
    given Configuration = Configuration.default.withSnakeCaseConstructorNames

    enum Intent derives ConfiguredEnumCodec, ConfiguredEnumJsonSchemaOf:
      case Lookup, SmallTalk, Remember

  test("an enum of field-less cases is a string enum, named as ConfiguredEnumCodec names them"):
    import Strings.*
    assertEquals((Intent.SmallTalk: Intent).asJson, Json.fromString("small_talk"))
    assertEquals(
      summon[JsonSchemaOf[Intent]].schema,
      Json.obj(
        "type" -> Json.fromString("string"),
        "enum" -> Json.arr(Json.fromString("lookup"), Json.fromString("small_talk"), Json.fromString("remember"))
      )
    )

  test("a string enum with a case that has fields does not compile"):
    val errors = compileErrors("""
      given io.circe.derivation.Configuration = io.circe.derivation.Configuration.default
      enum Bad derives ironmcp.schema.ConfiguredEnumJsonSchemaOf:
        case Plain
        case WithField(x: Int)
    """)
    assert(errors.contains("needs every case to have no fields"), errors)

  final case class Holder(tags: List[String], note: Option[Int]) derives JsonSchemaOf

  test("Option and List keep their own instances rather than being derived as sums"):
    val properties = summon[JsonSchemaOf[Holder]].schema.hcursor.downField("properties")
    assertEquals(properties.downField("tags").get[String]("type"), Right("array"))
    assertEquals(properties.downField("note").get[String]("type"), Right("integer"))

  final case class Inner(value: Int, hint: Option[String]) derives Decoder, JsonSchemaOf
  final case class Outer(name: String, inner: Option[Inner], items: List[Inner]) derives Decoder, JsonSchemaOf

  test("strict closes every object, requires every property, and makes optional ones nullable"):
    val strict = JsonSchemaOf.strict(summon[JsonSchemaOf[Outer]].schema).hcursor
    assertEquals(strict.get[Boolean]("additionalProperties"), Right(false))
    assertEquals(strict.get[List[String]]("required"), Right(List("name", "inner", "items")))

    val inner = strict.downField("properties").downField("inner").downField("anyOf")
    assertEquals(inner.downN(1).get[String]("type"), Right("null"))
    assertEquals(inner.downN(0).get[List[String]]("required"), Right(List("value", "hint")))
    assertEquals(inner.downN(0).get[Boolean]("additionalProperties"), Right(false))

    val item = strict.downField("properties").downField("items").downField("items")
    assertEquals(item.get[Boolean]("additionalProperties"), Right(false))

  test("what strict mode makes a model write still decodes: null is None"):
    val written = Json.obj(
      "name"  -> Json.fromString("x"),
      "inner" -> Json.Null,
      "items" -> Json.arr(Json.obj("value" -> Json.fromInt(1), "hint" -> Json.Null))
    )
    assertEquals(written.as[Outer], Right(Outer("x", None, List(Inner(1, None)))))

  test("strict turns a sum's oneOf into anyOf"):
    val strict = JsonSchemaOf.strict(summon[JsonSchemaOf[Shape]].schema)
    assert(strict.hcursor.downField("oneOf").failed)
    val square = strict.hcursor.downField("anyOf").downN(1).downField("properties").downField("Square")
    assertEquals(square.get[List[String]]("required"), Right(List("side", "label")))

  type Port = Port.T
  object Port extends RefinedType[Int, Interval.Closed[1, 65535] DescribedAs "TCP port"]

  final case class Listen(port: Port) derives Decoder, JsonSchemaOf

  test("an opaque RefinedType has the schema of the refined type it wraps"):
    val port = summon[JsonSchemaOf[Listen]].schema.hcursor.downField("properties").downField("port")
    assertEquals(port.get[String]("type"), Right("integer"))
    assertEquals(port.get[Int]("maximum"), Right(65535))
    assertEquals(port.get[String]("description"), Right("TCP port"))
    assert(Json.obj("port" -> Json.fromInt(0)).as[Listen].isLeft)
