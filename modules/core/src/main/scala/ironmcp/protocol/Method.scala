package ironmcp
package protocol

/** Every method this revision defines. Nothing dispatches on a bare string:
  * an unrecognised method becomes [[Method.Unknown]] and is answered with
  * `-32601`, never silently ignored.
  */
enum Method(val wire: String):
  // client -> server requests
  case Discover               extends Method("server/discover")
  case ToolsList              extends Method("tools/list")
  case ToolsCall              extends Method("tools/call")
  case ResourcesList          extends Method("resources/list")
  case ResourceTemplatesList  extends Method("resources/templates/list")
  case ResourcesRead          extends Method("resources/read")
  case PromptsList            extends Method("prompts/list")
  case PromptsGet             extends Method("prompts/get")
  case CompletionComplete     extends Method("completion/complete")
  case SubscriptionsListen    extends Method("subscriptions/listen")

  // notifications
  case Cancelled                 extends Method("notifications/cancelled")
  case Progress                  extends Method("notifications/progress")
  case LoggingMessage            extends Method("notifications/message")
  case ResourceUpdated           extends Method("notifications/resources/updated")
  case ResourcesListChanged      extends Method("notifications/resources/list_changed")
  case ToolsListChanged          extends Method("notifications/tools/list_changed")
  case PromptsListChanged        extends Method("notifications/prompts/list_changed")
  case SubscriptionsAcknowledged extends Method("notifications/subscriptions/acknowledged")

  case Unknown(raw: String) extends Method(raw)

object Method:
  private val specified: List[Method] = List(
    Discover,
    ToolsList,
    ToolsCall,
    ResourcesList,
    ResourceTemplatesList,
    ResourcesRead,
    PromptsList,
    PromptsGet,
    CompletionComplete,
    SubscriptionsListen,
    Cancelled,
    Progress,
    LoggingMessage,
    ResourceUpdated,
    ResourcesListChanged,
    ToolsListChanged,
    PromptsListChanged,
    SubscriptionsAcknowledged
  )

  def parse(raw: String): Method = specified.find(_.wire == raw).getOrElse(Unknown(raw))

  /** The three methods the spec permits to answer with `input_required`. */
  val inputCapable: Set[Method] = Set(ToolsCall, PromptsGet, ResourcesRead)
