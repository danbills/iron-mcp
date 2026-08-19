package ironmcp
package protocol

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.circe.given

/** A tool's input schema. The spec fixes `type: "object"`, so that is not a
  * field anyone can set wrongly — it is emitted by the codec.
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
      properties <- cursor.get[Option[Map[String, Json]]]("properties")
      required   <- cursor.get[Option[List[String]]]("required")
      all        <- cursor.as[JsonObject]
    yield ObjectSchema(
      properties.getOrElse(Map.empty),
      required.getOrElse(Nil),
      all.filterKeys(k => !Set("type", "properties", "required").contains(k))
    )
  }
