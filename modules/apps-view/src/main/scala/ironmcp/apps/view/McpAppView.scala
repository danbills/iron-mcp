package ironmcp.apps.view

import cats.effect.std.{Queue, Supervisor}
import cats.effect.syntax.all.*
import cats.effect.{Async, Concurrent, Deferred, Fiber, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import io.circe.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.protocol.*

/** A View's connection to its host: the View side of MCP Apps
  * (`io.modelcontextprotocol/ui`), JSON-RPC 2.0 over `postMessage`, as in
  * ext-apps' `App` class.
  *
  * Built by [[McpAppView.connect]] (or [[McpAppView.inWindow]] in a browser),
  * which has done the `ui/initialize` handshake by the time it yields.
  */
trait McpAppView[F[_]]:
  def protocolVersion: String
  def hostInfo: Implementation
  def hostCapabilities: HostCapabilities

  /** The host context, kept current by `ui/notifications/host-context-changed`. */
  def hostContext: Signal[F, HostContext]

  /** The tool call's input, result and cancellation, in the order the host
    * sent them. Buffered from before the handshake, so none is missed; meant
    * to be consumed by one stream.
    */
  def events: Stream[F, HostEvent]

  /** Calls a tool on the View's own server, through the host. */
  def callServerTool[A: Encoder.AsObject](name: String, arguments: A): F[CallToolResult]
  def callServerTool(name: String): F[CallToolResult]

  /** Reads a resource on the View's own server, through the host. */
  def readServerResource(uri: String): F[ReadResourceResult]

  /** Lists the resources of the View's own server, through the host. */
  def listServerResources(cursor: Option[String] = None): F[ListResourcesResult]

  /** Asks the host's model for a completion (`sampling/createMessage`). The
    * host may show it to the user first, or refuse; check
    * `hostCapabilities.sampling`.
    */
  def createSamplingMessage(params: CreateMessageParams): F[CreateMessageResult]

  /** Asks the host to open a URL (the sandbox blocks navigation). False if it refused. */
  def openLink(url: String): F[Boolean]

  /** Asks the host to save files: `Embedded` or `Link` content blocks. False if it refused. */
  def downloadFile(contents: List[ContentBlock]): F[Boolean]

  /** Sends a message to the conversation, as the user. False if the host refused. */
  def sendMessage(content: List[ContentBlock]): F[Boolean]

  /** Replaces what the model is told about the View's state, for its next turn. */
  def updateModelContext(content: List[ContentBlock] = Nil, structuredContent: Option[JsonObject] = None): F[Unit]

  /** Asks for a display mode; the host answers with the one it chose. */
  def requestDisplayMode(mode: DisplayMode): F[DisplayMode]

  /** Tells the host the View's content size, in pixels. See [[AutoResize]]. */
  def sendSizeChanged(width: Option[Int], height: Option[Int]): F[Unit]

  /** Asks the host to close the View. */
  def requestTeardown: F[Unit]

  /** Logs to the host (`notifications/message`), for debugging. */
  def log(level: LoggingLevel, data: Json, logger: Option[String] = None): F[Unit]

object McpAppView:

  /** ext-apps' `LATEST_PROTOCOL_VERSION`. */
  val ProtocolVersion: String = "2026-01-26"

  /** Connects in a browser: `postMessage` with `window.parent`. */
  def inWindow[F[_]](using F: Async[F])(
      appInfo: Implementation,
      capabilities: AppCapabilities = AppCapabilities(),
      onTeardown: F[Unit] = F.unit,
      tools: Option[ViewToolProvider[F]] = None
  ): Resource[F, McpAppView[F]] =
    HostPort.windowParent[F].flatMap(connect(appInfo, _, capabilities, onTeardown, tools))

  /** Starts reading from `port`, then does the handshake: `ui/initialize`,
    * then `ui/notifications/initialized`.
    *
    * The host's `ui/resource-teardown` runs `onTeardown` before it is
    * answered, so the View can save state first. Host requests run on their
    * own fibers, so a teardown handler may itself call the host, and the host
    * can cancel one (`notifications/cancelled`). Releasing the resource stops
    * reading; requests still waiting are not answered.
    *
    * With `tools`, the View offers tools to the host (`tools/list`,
    * `tools/call`) and declares `tools.listChanged`; each change to the list
    * is sent as `notifications/tools/list_changed`.
    */
  def connect[F[_]](using F: Concurrent[F])(
      appInfo: Implementation,
      port: HostPort[F],
      capabilities: AppCapabilities = AppCapabilities(),
      onTeardown: F[Unit] = F.unit,
      tools: Option[ViewToolProvider[F]] = None
  ): Resource[F, McpAppView[F]] =
    val declared =
      if tools.isDefined && capabilities.tools.isEmpty then capabilities.copy(tools = Some(ListChanged(Some(true)))) else capabilities
    for
      nextId     <- Resource.eval(Ref.of[F, Long](1L))
      pending    <- Resource.eval(Ref.of[F, Map[RequestId, Deferred[F, Either[JsonRpcError, Json]]]](Map.empty))
      running    <- Resource.eval(Ref.of[F, Map[RequestId, Fiber[F, Throwable, Unit]]](Map.empty))
      events     <- Resource.eval(Queue.unbounded[F, HostEvent])
      context    <- Resource.eval(SignallingRef.of[F, HostContext](HostContext.empty))
      supervisor <- Supervisor[F]
      wire        = Wire(port, nextId, pending)
      inbound     = Inbound(wire, events, context, supervisor, running, onTeardown, tools)
      _          <- port.incoming.evalMap(inbound.handle).compile.drain.background
      init <- Resource.eval(
                wire
                  .request(
                    "ui/initialize",
                    Json.obj(
                      "appInfo"         -> appInfo.asJson.deepDropNullValues,
                      "appCapabilities" -> declared.asJson,
                      "protocolVersion" -> ProtocolVersion.asJson
                    )
                  )
                  .flatMap(json => F.fromEither(json.as[InitializeResult]))
              )
      _ <- Resource.eval(context.set(init.hostContext))
      _ <- Resource.eval(wire.notify("ui/notifications/initialized", Json.obj()))
      _ <- tools.fold(Resource.unit[F])(
             _.changes.evalMap(_ => wire.notify("notifications/tools/list_changed", Json.obj())).compile.drain.background.void
           )
    yield View(wire, init, context, events)

  /** Requests out, responses matched back by id. */
  private final case class Wire[F[_]](
      port: HostPort[F],
      nextId: Ref[F, Long],
      pending: Ref[F, Map[RequestId, Deferred[F, Either[JsonRpcError, Json]]]]
  )(using F: Concurrent[F]):

    def send(message: JsonRpcMessage): F[Unit] = port.send(message.asJson)

    def request(method: String, params: Json): F[Json] =
      for
        id     <- nextId.getAndUpdate(_ + 1).map(RequestId.Num(_))
        answer <- Deferred[F, Either[JsonRpcError, Json]]
        _      <- pending.update(_ + (id -> answer))
        _      <- send(JsonRpcMessage.Request(id, methodName(method), Some(params)))
        // Cancelled while waiting (a timeout, say): the host is told, as
        // MCP's notifications/cancelled, so it can stop the work.
        result <- answer.get.onCancel(
                    pending.update(_ - id) >> notify("notifications/cancelled", Json.obj("requestId" -> id.asJson, "reason" -> "cancelled by the View".asJson))
                  )
        json   <- result.fold(error => F.raiseError(McpAppViewError(method, error)), F.pure)
      yield json

    def notify(method: String, params: Json): F[Unit] =
      send(JsonRpcMessage.Notification(methodName(method), Some(params)))

    def answer(id: RequestId, result: Either[JsonRpcError, Json]): F[Unit] =
      pending.modify(waiting => (waiting - id, waiting.get(id))).flatMap(_.traverse_(_.complete(result)))

  private def methodName(method: String): NonEmptyString = method.refineUnsafe

  /** An object without its absent fields: hosts validate with schemas that
    * accept a missing optional field but not a `null` one. Only the View's own
    * fields are cleaned; tool arguments and structured content go as given.
    */
  private def fields(entries: (String, Json)*): Json = Json.fromFields(entries.filterNot(_._2.isNull))

  /** MCP values (content blocks), whose codecs write `None` as `null`. */
  private def clean[A: Encoder](value: A): Json = value.asJson.deepDropNullValues

  /** Everything the host sends. */
  private final case class Inbound[F[_]](
      wire: Wire[F],
      events: Queue[F, HostEvent],
      context: SignallingRef[F, HostContext],
      supervisor: Supervisor[F],
      running: Ref[F, Map[RequestId, Fiber[F, Throwable, Unit]]],
      onTeardown: F[Unit],
      tools: Option[ViewToolProvider[F]]
  )(using F: Concurrent[F]):

    def handle(json: Json): F[Unit] =
      json.as[JsonRpcMessage].toOption.fold(F.unit) {
        case JsonRpcMessage.Success(id, result)          => wire.answer(id, Right(result))
        case JsonRpcMessage.Failure(Some(id), error)     => wire.answer(id, Left(error))
        case JsonRpcMessage.Failure(None, _)             => F.unit
        case JsonRpcMessage.Request(id, method, params)  => start(id, request(id, method, params))
        case JsonRpcMessage.Notification(method, params) => notification(method, params)
      }

    /** Runs a host request on its own fiber, kept by id until it ends so
      * `notifications/cancelled` can stop it. The gate keeps a quick request
      * from ending before it is recorded.
      */
    private def start(id: RequestId, work: F[Unit]): F[Unit] =
      for
        gate  <- Deferred[F, Unit]
        fiber <- supervisor.supervise(gate.get >> work.guarantee(running.update(_ - id)))
        _     <- running.update(_ + (id -> fiber))
        _     <- gate.complete(())
      yield ()

    private def request(id: RequestId, method: String, params: Option[Json]): F[Unit] =
      val reply: F[Either[JsonRpcError, Json]] = (method, tools) match
        case ("ui/resource-teardown", _) => onTeardown.attempt.as(Right(Json.obj()))
        case ("ping", _)                 => F.pure(Right(Json.obj()))
        case ("tools/list", Some(provider)) =>
          provider.list.map(list => Right(Json.obj("tools" -> Json.arr(list.map(clean(_))*))))
        case ("tools/call", Some(provider)) =>
          val cursor = params.getOrElse(Json.obj()).hcursor
          cursor.get[String]("name") match
            case Left(_) => F.pure(Left(invalid("tools/call needs a tool name")))
            case Right(name) =>
              provider
                .call(name, cursor.get[JsonObject]("arguments").getOrElse(JsonObject.empty))
                .map(_.toRight(invalid(s"Unknown tool: $name")).map(toolResult))
        case (other, _) =>
          F.pure(Left(JsonRpcError(ErrorCode.MethodNotFound, s"Method not found: $other".refineUnsafe)))
      reply.flatMap {
        case Right(result) => wire.send(JsonRpcMessage.Success(id, result))
        case Left(error)   => wire.send(JsonRpcMessage.Failure(Some(id), error))
      }

    private def invalid(message: String): JsonRpcError = JsonRpcError(ErrorCode.InvalidParams, message.refineUnsafe)

    /** A View tool's result as MCP's `CallToolResult`. Its structured content
      * goes as the tool wrote it, nulls included.
      */
    private def toolResult(result: CallToolResult): Json =
      fields(
        "content"           -> clean(result.content),
        "structuredContent" -> result.structuredContent.asJson,
        "isError"           -> result.isError.asJson,
        "_meta"             -> result._meta.asJson
      )

    private def notification(method: String, params: Option[Json]): F[Unit] = method match
      case "ui/notifications/tool-input" =>
        events.offer(HostEvent.ToolInput(Lenient.arguments(params)))
      case "ui/notifications/tool-input-partial" =>
        events.offer(HostEvent.ToolInputPartial(Lenient.arguments(params)))
      case "ui/notifications/tool-result" =>
        val raw = params.getOrElse(Json.obj())
        events.offer(
          Lenient.callToolResult(raw).fold(failure => HostEvent.Unreadable(method, raw, failure.getMessage), HostEvent.ToolResult(_))
        )
      case "ui/notifications/tool-cancelled" =>
        events.offer(HostEvent.ToolCancelled(params.flatMap(_.hcursor.get[String]("reason").toOption)))
      case "ui/notifications/host-context-changed" =>
        params.flatMap(_.asObject).fold(F.unit)(update => context.update(_.merge(update)))
      case "notifications/cancelled" =>
        params.flatMap(_.hcursor.get[RequestId]("requestId").toOption).fold(F.unit) { id =>
          running.modify(all => (all - id, all.get(id))).flatMap(_.traverse_(_.cancel))
        }
      case _ => F.unit

  private final case class View[F[_]](
      wire: Wire[F],
      init: InitializeResult,
      hostContext: Signal[F, HostContext],
      queue: Queue[F, HostEvent]
  )(using F: Concurrent[F])
      extends McpAppView[F]:

    def protocolVersion: String             = init.protocolVersion
    def hostInfo: Implementation            = init.hostInfo
    def hostCapabilities: HostCapabilities  = init.hostCapabilities
    def events: Stream[F, HostEvent]        = Stream.fromQueueUnterminated(queue)

    def callServerTool[A: Encoder.AsObject](name: String, arguments: A): F[CallToolResult] =
      wire
        .request("tools/call", Json.obj("name" -> name.asJson, "arguments" -> arguments.asJson))
        .flatMap(json => F.fromEither(Lenient.callToolResult(json)))

    def callServerTool(name: String): F[CallToolResult] = callServerTool(name, JsonObject.empty)

    def readServerResource(uri: String): F[ReadResourceResult] =
      wire.request("resources/read", Json.obj("uri" -> uri.asJson)).flatMap(json => F.fromEither(Lenient.readResourceResult(json)))

    def listServerResources(cursor: Option[String]): F[ListResourcesResult] =
      wire.request("resources/list", fields("cursor" -> cursor.asJson)).flatMap(json => F.fromEither(Lenient.listResourcesResult(json)))

    def createSamplingMessage(params: CreateMessageParams): F[CreateMessageResult] =
      wire.request("sampling/createMessage", clean(params)).flatMap(json => F.fromEither(json.as[CreateMessageResult]))

    def openLink(url: String): F[Boolean] = accepted("ui/open-link", Json.obj("url" -> url.asJson))

    def downloadFile(contents: List[ContentBlock]): F[Boolean] =
      accepted("ui/download-file", Json.obj("contents" -> clean(contents)))

    def sendMessage(content: List[ContentBlock]): F[Boolean] =
      accepted("ui/message", Json.obj("role" -> "user".asJson, "content" -> clean(content)))

    def updateModelContext(content: List[ContentBlock], structuredContent: Option[JsonObject]): F[Unit] =
      wire
        .request(
          "ui/update-model-context",
          fields("content" -> Option.when(content.nonEmpty)(clean(content)).asJson, "structuredContent" -> structuredContent.asJson)
        )
        .void

    def requestDisplayMode(mode: DisplayMode): F[DisplayMode] =
      wire
        .request("ui/request-display-mode", Json.obj("mode" -> mode.asJson))
        .flatMap(json => F.fromEither(json.hcursor.get[DisplayMode]("mode")))

    def sendSizeChanged(width: Option[Int], height: Option[Int]): F[Unit] =
      wire.notify("ui/notifications/size-changed", fields("width" -> width.asJson, "height" -> height.asJson))

    def requestTeardown: F[Unit] = wire.notify("ui/notifications/request-teardown", Json.obj())

    def log(level: LoggingLevel, data: Json, logger: Option[String]): F[Unit] =
      wire.notify("notifications/message", fields("level" -> level.asJson, "logger" -> logger.asJson, "data" -> data))

    /** `{isError?: boolean}` results: true unless the host says it refused. */
    private def accepted(method: String, params: Json): F[Boolean] =
      wire.request(method, params).map(json => !json.hcursor.get[Boolean]("isError").contains(true))
