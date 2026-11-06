package ironmcp
package apps

import scala.util.matching.Regex

/** Builds a View's self-contained HTML from a page, a script and styles, such
  * as a Scala.js View (`iron-mcp-apps-view`) linked by `fullLinkJS`. The
  * default sandbox loads nothing from the network, so both go inline.
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

  /** Where a template wants its styles: `<!-- mcp-app-style -->`. */
  val StyleSlot: String = "<!-- mcp-app-style -->"

  /** `html` with `css` inlined as a `<style>`: at [[StyleSlot]] if the page
    * has one, else before `</head>`, else at the start.
    */
  def inlineStyle(html: String, css: String): String =
    val tag = s"<style>\n${escapeStyle(css)}\n</style>"
    if html.contains(StyleSlot) then html.replace(StyleSlot, tag)
    else
      val head = html.toLowerCase.indexOf("</head>")
      if head >= 0 then html.substring(0, head) + tag + "\n" + html.substring(head) else tag + "\n" + html

  /** `</style` would end the element early. In CSS it can occur only inside a
    * string or a comment, where `<\/style` means the same.
    */
  def escapeStyle(css: String): String =
    CloseStyle.replaceAllIn(css, m => Regex.quoteReplacement("<\\/" + m.group(1)))

  /** The two sequences that would end or confuse a `<script>` element early.
    * In compiled JS they occur only inside string and regex literals, where
    * the escaped forms mean the same.
    */
  def escape(script: String): String =
    val noClose = CloseScript.replaceAllIn(script, m => Regex.quoteReplacement("<\\/" + m.group(1)))
    noClose.replace("<!--", "<\\!--")

  private val CloseScript = "(?i)</(script)".r
  private val CloseStyle  = "(?i)</(style)".r
