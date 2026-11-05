package ironmcp
package protocol

import io.circe.*
import io.circe.syntax.*

import scala.collection.immutable.ListMap

/** A tool's input schema. The spec fixes `type: "object"`, so that is not a
  * field anyone can set wrongly — it is emitted by the codec.
  *
  * Decoding keeps the properties in their declared order (a `ListMap`); pass a
  * `ListMap` when building one by hand with more than four properties.
  */
final case class ObjectSchema(
    properties: Map[String, Json] = Map.empty,
    required: List[String] = Nil,
    additional: JsonObject = JsonObject.empty
)

object ObjectSchema:
  val empty: ObjectSchema = ObjectSchema()

  given Encoder[ObjectSchema] = schema =>
    Json
      .obj(
        "type"       -> Json.fromString("object"),
        "properties" -> schema.properties.asJson
      )
      .deepMerge(if schema.required.isEmpty then Json.obj() else Json.obj("required" -> schema.required.asJson))
      .deepMerge(Json.fromJsonObject(schema.additional))

  given Decoder[ObjectSchema] = Decoder.instance { cursor =>
    for
      tpe <- cursor.get[String]("type")
      _ <- Either.cond(
             tpe == "object",
             (),
             DecodingFailure(s"an input schema must have type 'object', not '$tpe'", cursor.history)
           )
      // Keep the declared order: a plain Map forgets it past four entries, and the
      // order is part of what a model or a form shows.
      properties <- cursor.get[Option[JsonObject]]("properties").map(_.map(obj => ListMap.from(obj.toList)))
      required   <- cursor.get[Option[List[String]]]("required")
      all        <- cursor.as[JsonObject]
    yield ObjectSchema(
      properties.getOrElse(ListMap.empty),
      required.getOrElse(Nil),
      all.filterKeys(k => !Set("type", "properties", "required").contains(k))
    )
  }
