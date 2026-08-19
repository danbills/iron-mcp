package ironmcp
package schema

import scala.quoted.*

/** Reads an Iron constraint *type* and returns the JSON Schema keywords it
  * implies, as a JSON string literal baked into the bytecode.
  *
  * This is a macro rather than a set of `given`s because constraints compose
  * with `&`, and Scala cannot decompose an intersection type into two unknowns
  * during implicit search — `ConstraintSchema[A & B]` has infinitely many
  * candidate splits, so the search simply fails. Walking the `TypeRepr`
  * sidesteps that and handles arbitrary nesting for free.
  *
  * It runs entirely at compile time: no reflection survives into the runtime,
  * so Scala Native and GraalVM are unaffected.
  */
private[schema] object ConstraintSchemaMacros:

  def instanceOf[C: Type](using Quotes): Expr[ConstraintSchema[C]] =
    import quotes.reflect.*
    val constraint = TypeRepr.of[C]
    // Only the OUTERMOST DescribedAs becomes the field description. Iron's own
    // aliases are DescribedAs underneath (GreaterEqual carries "Should be
    // greater than or equal to 1"), so reading every level would smear
    // generated constraint prose across every field. Reading only the top
    // means a description appears when someone wrote one -- and a bare
    // `Interval.Closed[1, 10]` still contributes its own readable message,
    // which is true and useful to a model.
    val description = describe(constraint).map(text => "description" -> text).toList
    val keywords    = Expr(render(description ++ read(constraint)))
    '{ ConstraintSchema.fromJson[C]($keywords) }

  /** The message on the outermost `DescribedAs`, if there is one. */
  private def describe(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[String] =
    import quotes.reflect.*
    tpe.dealias match
      case AppliedType(constructor, List(_, message)) if constructor.typeSymbol.name == "DescribedAs" =>
        literal(message)
      case _ => None

  /** A tiny JSON writer: the macro cannot lift a `circe.Json`, and pulling
    * circe into the macro's own classpath is not worth it for object literals.
    */
  private def render(keywords: List[(String, String)]): String =
    keywords.map((key, value) => s""""$key":$value""").mkString("{", ",", "}")

  private def read(using Quotes)(tpe: quotes.reflect.TypeRepr): List[(String, String)] =
    import quotes.reflect.*

    tpe.dealias match
      // Conjunction: every keyword on both sides applies.
      case AndType(left, right) =>
        merge(read(left), read(right))

      // Iron spells inclusive bounds as `Greater[V] | StrictEqual[V]`.
      case OrType(left, right) =>
        (constraintOf(left), constraintOf(right)) match
          case (Some(("Greater", value)), Some(("StrictEqual", _))) => List("minimum" -> value)
          case (Some(("StrictEqual", _)), Some(("Greater", value))) => List("minimum" -> value)
          case (Some(("Less", value)), Some(("StrictEqual", _)))    => List("maximum" -> value)
          case (Some(("StrictEqual", _)), Some(("Less", value)))    => List("maximum" -> value)
          case _                                                    => Nil

      case applied @ AppliedType(constructor, args) =>
        constructor.typeSymbol.name match
          // Iron's human-readable wrapper; the meaning is underneath.
          case "DescribedAs" => read(args.head)

          case "Greater" => literal(args.head).map(v => List("exclusiveMinimum" -> v)).getOrElse(Nil)
          case "Less"    => literal(args.head).map(v => List("exclusiveMaximum" -> v)).getOrElse(Nil)
          case "StrictEqual" => literal(args.head).map(v => List("const" -> v)).getOrElse(Nil)
          case "Match"       => literal(args.head).map(v => List("pattern" -> v)).getOrElse(Nil)

          // A numeric constraint applied to the *length* of the value.
          case "Length" => asLength(read(args.head))

          // The only negation with a clean schema reading is "not empty".
          case "Not" =>
            read(args.head) match
              case keywords if keywords.contains("minLength" -> "0") && keywords.contains("maxLength" -> "0") =>
                List("minLength" -> "1")
              case _ => Nil

          case _ => Nil

      case _ => Nil

  /** Recognises a single applied constraint and its literal argument. */
  private def constraintOf(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[(String, String)] =
    import quotes.reflect.*
    tpe.dealias match
      case AppliedType(constructor, args) =>
        literal(args.head).map(constructor.typeSymbol.name -> _)
      case _ => None

  private def literal(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[String] =
    import quotes.reflect.*
    tpe.dealias match
      case ConstantType(constant) =>
        constant.value match
          case value: String  => Some("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
          case value: Int     => Some(value.toString)
          case value: Long    => Some(value.toString)
          case value: Short   => Some(value.toString)
          case value: Byte    => Some(value.toString)
          case value: Double  => Some(value.toString)
          case value: Float   => Some(value.toString)
          case value: Boolean => Some(value.toString)
          case _              => None
      case _ => None

  private def asLength(keywords: List[(String, String)]): List[(String, String)] =
    keywords.flatMap {
      case ("minimum", value)          => List("minLength" -> value)
      case ("maximum", value)          => List("maxLength" -> value)
      case ("exclusiveMinimum", value) => List("minLength" -> shift(value, 1))
      case ("exclusiveMaximum", value) => List("maxLength" -> shift(value, -1))
      case ("const", value)            => List("minLength" -> value, "maxLength" -> value)
      case other                       => List(other)
    }

  private def shift(value: String, by: Int): String =
    value.toIntOption.fold(value)(number => (number + by).toString)

  private def merge(
      left: List[(String, String)],
      right: List[(String, String)]
  ): List[(String, String)] =
    left.filterNot((key, _) => right.exists(_._1 == key)) ++ right
