package ironmcp.apps.view

import io.circe.{Decoder, DecodingFailure, Json, JsonObject}
import ironmcp.protocol.{CallToolResult, ContentBlock, ReadResourceResult}

extension (result: CallToolResult)
  /** `structuredContent` decoded as `A`. The data is untrusted (it came
    * through the host from a server), so decoding is the validation: render
    * only what decodes.
    */
  def structuredAs[A: Decoder]: Decoder.Result[A] =
    result.structuredContent
      .toRight(DecodingFailure("the result has no structuredContent", Nil))
      .flatMap(_.as[A])

  /** The text blocks, joined by newlines. */
  def textContent: String =
    result.content.collect { case ContentBlock.Text(text, _, _) => text }.mkString("\n")

  def failed: Boolean = result.isError.contains(true)

/** Results arrive through the host, possibly from a server on an earlier
  * revision that does not send the fields 2026-07-28 requires. A View has no
  * use for those fields, so they are filled with their defaults before
  * decoding.
  */
private[view] object Lenient:

  def callToolResult(json: Json): Decoder.Result[CallToolResult] =
    withDefaults(json, "content" -> Json.arr(), "resultType" -> Json.fromString("complete")).as[CallToolResult]

  def readResourceResult(json: Json): Decoder.Result[ReadResourceResult] =
    withDefaults(
      json,
      "resultType" -> Json.fromString("complete"),
      "ttlMs"      -> Json.fromLong(0L),
      "cacheScope" -> Json.fromString("private")
    ).as[ReadResourceResult]

  private def withDefaults(json: Json, defaults: (String, Json)*): Json =
    json.mapObject(obj => defaults.foldLeft(obj)((acc, field) => if acc.contains(field._1) then acc else acc.add(field._1, field._2)))

  def arguments(params: Option[Json]): JsonObject =
    params.flatMap(_.hcursor.get[JsonObject]("arguments").toOption).getOrElse(JsonObject.empty)
