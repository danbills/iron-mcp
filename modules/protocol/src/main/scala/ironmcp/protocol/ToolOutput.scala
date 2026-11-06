package ironmcp
package protocol

import cats.Show
import io.circe.{Encoder, Json, JsonObject}
import ironmcp.schema.JsonSchemaOf

/** What a tool's handler returns, and what that type says about the tool:
  * a server's (`McpTool`, which re-exports this as `ironmcp.server.ToolOutput`)
  * or a View's (`iron-mcp-apps-view`'s `ViewTool`).
  *
  * Return a case class and the tool advertises an `outputSchema` derived from it — the same type its
  * `structuredContent` is encoded from, so the declared shape and the result cannot drift apart. The text block is
  * the serialized JSON, as the spec recommends, unless a `Show` for the type renders something better for a reader.
  *
  * Return `Either[String, O]` when the tool can fail at its job: `Left` becomes an `isError` result the model can
  * read. Return `CallToolResult` to build the result by hand; such a tool declares no output schema.
  */
trait ToolOutput[O]:
  /** The `outputSchema` advertised in `tools/list`, when the output has a declared shape. */
  def schema: Option[JsonObject]
  def result(value: O): CallToolResult

object ToolOutput extends StructuredOutput:

  given raw: ToolOutput[CallToolResult] with
    val schema: Option[JsonObject]               = None
    def result(value: CallToolResult): CallToolResult = value

  /** Plain text: a text block and no output schema. */
  given text: ToolOutput[String] with
    val schema: Option[JsonObject]            = None
    def result(value: String): CallToolResult = CallToolResult.text(value)

  given either[O](using output: ToolOutput[O]): ToolOutput[Either[String, O]] with
    val schema: Option[JsonObject] = output.schema
    def result(value: Either[String, O]): CallToolResult = value.fold(CallToolResult.failed, output.result)

  /** A structured output that a `Show` renders for the text block. */
  given shown[O](using Encoder.AsObject[O], JsonSchemaOf[O], Show[O]): ToolOutput[O] = structured((value, _) => Show[O].show(value))

private[protocol] trait StructuredOutput:

  /** A structured output whose text block is its serialized JSON. */
  given serialized[O](using Encoder.AsObject[O], JsonSchemaOf[O]): ToolOutput[O] = structured((_, json) => json.noSpaces)

  protected def structured[O](text: (O, Json) => String)(using encoder: Encoder.AsObject[O], shape: JsonSchemaOf[O]): ToolOutput[O] =
    new ToolOutput[O]:
      val schema: Option[JsonObject] = shape.schema.asObject
      def result(value: O): CallToolResult =
        // An absent optional field is left out, not written as null: the schema
        // leaves it out of `required` and does not admit null.
        val json = encoder(value).deepDropNullValues
        CallToolResult.structured(text(value, json), json)
