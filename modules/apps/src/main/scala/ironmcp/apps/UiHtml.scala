package ironmcp
package apps

import scala.util.matching.Regex

/** Builds a View's self-contained HTML from a page and a script, such as a
  * Scala.js View (`iron-mcp-apps-view`) linked by `fullLinkJS`. The default
  * sandbox loads nothing from the network, so the script goes inline.
  */
object UiHtml:

  /** Where a template wants the script: `<!-- mcp-app-script -->`. */
  val ScriptSlot: String = "<!-- mcp-app-script -->"

  /** `html` with `script` inlined as a classic `<script>`: at [[ScriptSlot]]
    * if the page has one, else before `</body>`, else at the end.
    */
  def inlineScript(html: String, script: String): String =
    val tag = s"<script>\n${escape(script)}\n</script>"
    if html.contains(ScriptSlot) then html.replace(ScriptSlot, tag)
    else
      val body = html.toLowerCase.lastIndexOf("</body>")
      if body >= 0 then html.substring(0, body) + tag + "\n" + html.substring(body) else html + "\n" + tag

  /** The two sequences that would end or confuse a `<script>` element early.
    * In compiled JS they occur only inside string and regex literals, where
    * the escaped forms mean the same.
    */
  def escape(script: String): String =
    val noClose = CloseScript.replaceAllIn(script, m => Regex.quoteReplacement("<\\/" + m.group(1)))
    noClose.replace("<!--", "<\\!--")

  private val CloseScript = "(?i)</(script)".r
