package ironmcp
package protocol

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.circe.given

/** The contents of a resource: text or base64 blob, never both. */
enum ResourceContents:
  case Text(uri: Uri, text: String, mimeType: Option[MimeType] = None, _meta: Option[MetaObject] = None)
  case Blob(uri: Uri, blob: Base64, mimeType: Option[MimeType] = None, _meta: Option[MetaObject] = None)

object ResourceContents:
  import MetaObject.given

  given Encoder[ResourceContents] =
    case ResourceContents.Text(uri, text, mimeType, meta) =>
      base(uri, mimeType, meta).deepMerge(Json.obj("text" -> Json.fromString(text)))
    case ResourceContents.Blob(uri, blob, mimeType, meta) =>
      base(uri, mimeType, meta).deepMerge(Json.obj("blob" -> Json.fromString(blob)))

  private def base(uri: Uri, mimeType: Option[MimeType], meta: Option[MetaObject]): Json =
    Json
      .obj("uri" -> uri.asJson)
      .deepMerge(mimeType.fold(Json.obj())(m => Json.obj("mimeType" -> m.asJson)))
      .deepMerge(meta.fold(Json.obj())(m => Json.obj("_meta" -> m.asJson)))

  given Decoder[ResourceContents] = Decoder.instance { cursor =>
    for
      uri      <- cursor.get[Uri]("uri")
      mimeType <- cursor.get[Option[MimeType]]("mimeType")
      meta     <- cursor.get[Option[MetaObject]]("_meta")
      text     <- cursor.get[Option[String]]("text")
      blob     <- cursor.get[Option[Base64]]("blob")
      contents <- (text, blob) match
                    case (Some(t), None) => Right(ResourceContents.Text(uri, t, mimeType, meta))
                    case (None, Some(b)) => Right(ResourceContents.Blob(uri, b, mimeType, meta))
                    case (Some(_), Some(_)) =>
                      Left(DecodingFailure("resource contents carry both text and blob", cursor.history))
                    case (None, None) =>
                      Left(DecodingFailure("resource contents carry neither text nor blob", cursor.history))
    yield contents
  }

/** A resource as advertised by `resources/list`, and — with a `type` tag — as
  * a content block linking to one.
  */
final case class Resource(
    uri: Uri,
    name: NonEmptyString,
    title: Option[NonEmptyString] = None,
    description: Option[NonEmptyString] = None,
    mimeType: Option[MimeType] = None,
    annotations: Option[Annotations] = None,
    size: Option[Long] = None,
    icons: Option[List[Icon]] = None,
    _meta: Option[MetaObject] = None
) derives Codec.AsObject

/** Content blocks are a union tagged by a `type` field, which no derivation
  * scheme produces, so the codec is written out. Everything it delegates to is
  * still derived.
  */
enum ContentBlock:
  case Text(text: String, annotations: Option[Annotations] = None, _meta: Option[MetaObject] = None)
  case Image(data: Base64, mimeType: MimeType, annotations: Option[Annotations] = None, _meta: Option[MetaObject] = None)
  case Audio(data: Base64, mimeType: MimeType, annotations: Option[Annotations] = None, _meta: Option[MetaObject] = None)
  case Link(resource: Resource)
  case Embedded(resource: ResourceContents, annotations: Option[Annotations] = None, _meta: Option[MetaObject] = None)

object ContentBlock:
  import MetaObject.given

  private def tagged(tpe: String, fields: (String, Json)*): Json =
    Json.obj((("type" -> Json.fromString(tpe)) +: fields)*)

  private def decorations(annotations: Option[Annotations], meta: Option[MetaObject]): Json =
    annotations.fold(Json.obj())(a => Json.obj("annotations" -> a.asJson))
      .deepMerge(meta.fold(Json.obj())(m => Json.obj("_meta" -> m.asJson)))

  given Encoder[ContentBlock] =
    case ContentBlock.Text(text, annotations, meta) =>
      tagged("text", "text" -> Json.fromString(text)).deepMerge(decorations(annotations, meta))
    case ContentBlock.Image(data, mimeType, annotations, meta) =>
      tagged("image", "data" -> data.asJson, "mimeType" -> mimeType.asJson)
        .deepMerge(decorations(annotations, meta))
    case ContentBlock.Audio(data, mimeType, annotations, meta) =>
      tagged("audio", "data" -> data.asJson, "mimeType" -> mimeType.asJson)
        .deepMerge(decorations(annotations, meta))
    case ContentBlock.Link(resource) =>
      resource.asJson.deepMerge(Json.obj("type" -> Json.fromString("resource_link")))
    case ContentBlock.Embedded(resource, annotations, meta) =>
      tagged("resource", "resource" -> resource.asJson).deepMerge(decorations(annotations, meta))

  given Decoder[ContentBlock] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "text" =>
        for
          text        <- cursor.get[String]("text")
          annotations <- cursor.get[Option[Annotations]]("annotations")
          meta        <- cursor.get[Option[MetaObject]]("_meta")
        yield ContentBlock.Text(text, annotations, meta)
      case "image" =>
        binary(cursor).map(ContentBlock.Image.apply.tupled)
      case "audio" =>
        binary(cursor).map(ContentBlock.Audio.apply.tupled)
      case "resource_link" =>
        cursor.as[Resource].map(ContentBlock.Link.apply)
      case "resource" =>
        for
          resource    <- cursor.get[ResourceContents]("resource")
          annotations <- cursor.get[Option[Annotations]]("annotations")
          meta        <- cursor.get[Option[MetaObject]]("_meta")
        yield ContentBlock.Embedded(resource, annotations, meta)
      case other =>
        Left(DecodingFailure(s"unknown content block type: $other", cursor.history))
    }
  }

  private def binary(
      cursor: HCursor
  ): Decoder.Result[(Base64, MimeType, Option[Annotations], Option[MetaObject])] =
    for
      data        <- cursor.get[Base64]("data")
      mimeType    <- cursor.get[MimeType]("mimeType")
      annotations <- cursor.get[Option[Annotations]]("annotations")
      meta        <- cursor.get[Option[MetaObject]]("_meta")
    yield (data, mimeType, annotations, meta)
