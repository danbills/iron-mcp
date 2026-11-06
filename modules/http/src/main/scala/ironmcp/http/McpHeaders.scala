package ironmcp
package http

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

import org.typelevel.ci.CIString

/** The request headers the Streamable HTTP binding mirrors from the body, and
  * its rule for values that are not safe in a header.
  */
object McpHeaders:

  val ProtocolVersion: CIString = CIString("MCP-Protocol-Version")
  val Method: CIString          = CIString("Mcp-Method")
  val Name: CIString            = CIString("Mcp-Name")

  /** The methods whose target is mirrored into `Mcp-Name`, and the param it comes from. */
  def nameParam(method: String): Option[String] = method match
    case "tools/call" | "prompts/get" => Some("name")
    case "resources/read"             => Some("uri")
    case _                            => None

  private val Prefix = "=?base64?"
  private val Suffix = "?="

  /** A value as it may go in a header: as-is when it is visible ASCII with no
    * leading or trailing whitespace, otherwise `=?base64?…?=`. A plain value
    * that already looks like the sentinel is encoded too, so it cannot be misread.
    */
  def encode(value: String): String =
    val safe = value.nonEmpty && value.forall(c => c == ' ' || c == '\t' || (c >= 0x21 && c <= 0x7e)) &&
      value.head != ' ' && value.head != '\t' && value.last != ' ' && value.last != '\t'
    val looksEncoded = value.startsWith(Prefix) && value.endsWith(Suffix)
    if safe && !looksEncoded then value
    else Prefix + Base64.getEncoder.encodeToString(value.getBytes(UTF_8)) + Suffix

  /** The value a header carries, decoding the sentinel; `None` if it is malformed. */
  def decode(raw: String): Option[String] =
    if raw.startsWith(Prefix) && raw.endsWith(Suffix) && raw.length >= Prefix.length + Suffix.length then
      scala.util.Try(String(Base64.getDecoder.decode(raw.substring(Prefix.length, raw.length - Suffix.length)), UTF_8)).toOption
    else Some(raw)
