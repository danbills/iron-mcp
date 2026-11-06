package ironmcp
package protocol

import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.circe.given

/** What is being completed: a prompt argument or a resource-template variable. */
enum CompletionReference:
  case ForPrompt(name: NonEmptyString, title: Option[NonEmptyString] = None)
  case ForResourceTemplate(uri: Uri)

object CompletionReference:
  given Encoder[CompletionReference] =
    case CompletionReference.ForPrompt(name, title) =>
      Json
        .obj("type" -> Json.fromString("ref/prompt"), "name" -> name.asJson)
        .deepMerge(title.fold(Json.obj())(t => Json.obj("title" -> t.asJson)))
    case CompletionReference.ForResourceTemplate(uri) =>
      Json.obj("type" -> Json.fromString("ref/resource"), "uri" -> uri.asJson)

  given Decoder[CompletionReference] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "ref/prompt" =>
        for
          name  <- cursor.get[NonEmptyString]("name")
          title <- cursor.get[Option[NonEmptyString]]("title")
        yield CompletionReference.ForPrompt(name, title)
      case "ref/resource" =>
        cursor.get[Uri]("uri").map(CompletionReference.ForResourceTemplate.apply)
      case other =>
        Left(DecodingFailure(s"unknown completion reference type: $other", cursor.history))
    }
  }

final case class CompletionArgument(name: NonEmptyString, value: String) derives Codec.AsObject
final case class CompletionContext(arguments: Option[Map[String, String]] = None) derives Codec.AsObject

final case class CompleteParams(
    ref: CompletionReference,
    argument: CompletionArgument,
    _meta: RequestMeta,
    context: Option[CompletionContext] = None
) derives Codec.AsObject

/** The spec caps a completion response at 100 values. */
final case class Completion(
    values: List[String],
    total: Option[Long] = None,
    hasMore: Option[Boolean] = None
) derives Codec.AsObject

final case class CompleteResult(
    completion: Completion,
    resultType: ResultType = ResultType.Complete,
    _meta: Option[ResultMeta] = None
) derives Codec.AsObject
