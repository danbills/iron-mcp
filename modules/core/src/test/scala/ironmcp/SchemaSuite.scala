package ironmcp

import io.circe.Json
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.circe.given
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import munit.FunSuite

class SchemaSuite extends FunSuite:

  final case class Greet(name: NonEmptyString, times: Int :| Interval.Closed[1, 10]) derives JsonSchemaOf

  test("the schema is derived from the Iron constraints, not written by hand"):
    val schema = summon[JsonSchemaOf[Greet]].schema
    val name   = schema.hcursor.downField("properties").downField("name")
    val times  = schema.hcursor.downField("properties").downField("times")

    assertEquals(schema.hcursor.get[String]("type"), Right("object"))
    assertEquals(name.get[String]("type"), Right("string"))
    assertEquals(name.get[Int]("minLength"), Right(1))          // from Not[Empty]
    assertEquals(times.get[String]("type"), Right("integer"))
    assertEquals(times.get[Int]("minimum"), Right(1))            // from Interval.Closed
    assertEquals(times.get[Int]("maximum"), Right(10))
    assertEquals(schema.hcursor.get[List[String]]("required"), Right(List("name", "times")))

  final case class WithOptional(required: NonEmptyString, note: Option[String]) derives JsonSchemaOf

  test("only Option makes a field optional"):
    val schema = summon[JsonSchemaOf[WithOptional]].schema
    assertEquals(schema.hcursor.get[List[String]]("required"), Right(List("required")))
    assertEquals(
      schema.hcursor.downField("properties").downField("note").get[String]("type"),
      Right("string")
    )

  final case class Bounded(
      port: Int :| Interval.Closed[1, 65535],
      ratio: Double :| GreaterEqual[0.0],
      code: String :| Match["^[A-Z]{3}$"],
      tags: List[NonEmptyString]
  ) derives JsonSchemaOf

  test("each constraint shape maps to its JSON Schema keyword"):
    val properties = summon[JsonSchemaOf[Bounded]].schema.hcursor.downField("properties")
    assertEquals(properties.downField("port").get[Int]("maximum"), Right(65535))
    assertEquals(properties.downField("ratio").get[Double]("minimum"), Right(0.0))
    assertEquals(properties.downField("code").get[String]("pattern"), Right("^[A-Z]{3}$"))
    assertEquals(properties.downField("tags").get[String]("type"), Right("array"))
    assertEquals(
      properties.downField("tags").downField("items").get[Int]("minLength"),
      Right(1)
    )

  final case class Lengthy(short: String :| MinLength[2], capped: String :| MaxLength[8]) derives JsonSchemaOf

  test("Length constraints become length keywords, not numeric ones"):
    val properties = summon[JsonSchemaOf[Lengthy]].schema.hcursor.downField("properties")
    assertEquals(properties.downField("short").get[Int]("minLength"), Right(2))
    assertEquals(properties.downField("capped").get[Int]("maxLength"), Right(8))
    assertEquals(properties.downField("short").get[Int]("minimum").isLeft, true)

  final case class Exotic(value: Int :| Multiple[7]) derives JsonSchemaOf

  test("a constraint with no schema reading degrades quietly, still enforced"):
    val property = summon[JsonSchemaOf[Exotic]].schema.hcursor.downField("properties").downField("value")
    assertEquals(property.get[String]("type"), Right("integer"))
    // Multiple[7] has no keyword here, but the decoder still rejects 8:
    assertEquals(Json.fromInt(8).as[Int :| Multiple[7]].isLeft, true)
    assertEquals(Json.fromInt(14).as[Int :| Multiple[7]].isRight, true)

  type Recipient = String :| (Not[Empty] DescribedAs "Who to greet")
  type Repeats   = Int :| (Interval.Closed[1, 10] DescribedAs "How many times to repeat")

  final case class Described(name: Recipient, times: Repeats) derives JsonSchemaOf

  test("a DescribedAs on the type becomes the field description"):
    val properties = summon[JsonSchemaOf[Described]].schema.hcursor.downField("properties")
    assertEquals(properties.downField("name").get[String]("description"), Right("Who to greet"))
    assertEquals(properties.downField("times").get[String]("description"), Right("How many times to repeat"))

  test("a description does not displace the constraint keywords"):
    val times = summon[JsonSchemaOf[Described]].schema.hcursor.downField("properties").downField("times")
    assertEquals(times.get[Int]("minimum"), Right(1))
    assertEquals(times.get[Int]("maximum"), Right(10))
    assertEquals(times.get[String]("type"), Right("integer"))

  final case class Undescribed(name: NonEmptyString) derives JsonSchemaOf

  test("Iron's internal messages on nested aliases do not leak as descriptions"):
    // NonEmptyString is Not[Empty]; Empty is a DescribedAs("Should be empty")
    // one level down, and must not surface as the field's description.
    val name = summon[JsonSchemaOf[Undescribed]].schema.hcursor.downField("properties").downField("name")
    assertEquals(name.get[String]("description").isLeft, true)
    assertEquals(name.get[Int]("minLength"), Right(1))

  final case class BareInterval(times: Int :| Interval.Closed[1, 10]) derives JsonSchemaOf

  test("a bare Iron alias contributes its own message as the description"):
    // Accepted behaviour, not an accident: the outermost DescribedAs here is
    // Iron's, and its message is a true description of the constraint.
    val times = summon[JsonSchemaOf[BareInterval]].schema.hcursor.downField("properties").downField("times")
    assertEquals(times.get[String]("description"), Right("Should be included in [1, 10]"))
