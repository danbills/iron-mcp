package ironmcp
package client

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import munit.CatsEffectSuite

/** McpClient against a transport that answers whatever the test says: the replies a well-behaved server never
  * sends, and the calls ClientSuite makes only with arguments.
  */
class McpClientSuite extends CatsEffectSuite:

  /** Records each request and answers it with `reply`. */
  private def scripted(
      reply: JsonRpcMessage.Request => JsonRpcMessage,
      handlers: InputHandlers[IO] = InputHandlers.none[IO]
  ): IO[(McpClient[IO], IO[List[JsonRpcMessage.Request]])] =
    for
      sent <- IO.ref(List.empty[JsonRpcMessage.Request])
      transport = new ClientTransport[IO]:
                    def request(message: JsonRpcMessage.Request)     = sent.update(_ :+ message).as(reply(message))
                    def notify(message: JsonRpcMessage.Notification) = IO.unit
      client <- McpClient[IO](transport, handlers = handlers)
    yield (client, sent.get)

  private def ok(result: Json): JsonRpcMessage.Request => JsonRpcMessage = r => JsonRpcMessage.Success(r.id, result)

  private def params(r: JsonRpcMessage.Request): JsonObject = r.params.flatMap(_.asObject).getOrElse(JsonObject.empty)

  test("an empty method name is refused before anything is sent"):
    for
      (client, sent) <- scripted(ok(Json.obj()))
      error          <- client.request[Json]("").attempt
      none           <- sent
    yield
      assertEquals(error.left.map(_.getClass), Left(classOf[McpClientError.Malformed]))
      assertEquals(none, Nil)

  test("a result that doesn't decode as the answer is Malformed, naming the method"):
    scripted(ok(Json.obj("tools" -> Json.fromString("not a list")))).flatMap((client, _) => client.listTools().attempt).map {
      case Left(McpClientError.Malformed(reason)) => assert(reason.startsWith("tools/list:"), reason)
      case other                                  => fail(s"$other")
    }

  test("a reply that is neither a success nor a failure is Malformed"):
    scripted(_ => JsonRpcMessage.Notification("x", None)).flatMap((client, _) => client.discover.attempt).map {
      case Left(McpClientError.Malformed(reason)) => assert(reason.contains("unexpected"), reason)
      case other                                  => fail(s"$other")
    }

  test("the listing calls send no cursor when given none, and getPrompt no arguments"):
    val empty = Json.obj("ttlMs" -> 0.asJson, "cacheScope" -> "private".asJson)
    for
      (client, sent) <- scripted {
                          case r if (r.method: String) == "prompts/get" => JsonRpcMessage.Success(r.id, Json.obj("messages" -> Json.arr()))
                          case r if (r.method: String) == "resources/list" => JsonRpcMessage.Success(r.id, empty.deepMerge(Json.obj("resources" -> Json.arr())))
                          case r => JsonRpcMessage.Success(r.id, empty.deepMerge(Json.obj("resourceTemplates" -> Json.arr())))
                        }
      _              <- client.listResources()
      _              <- client.listResourceTemplates()
      _              <- client.getPrompt("summarize")
      requests       <- sent
    yield
      assertEquals(requests.map(_.method: String), List("resources/list", "resources/templates/list", "prompts/get"))
      assert(requests.take(2).forall(r => !params(r).contains("cursor")))
      assertEquals(params(requests(2))("arguments"), Some(Json.obj()))

  test("complete sends context arguments only when there are some"):
    for
      (client, sent) <- scripted(ok(Json.obj("completion" -> Json.obj("values" -> Json.arr()))))
      _              <- client.complete(CompletionReference.ForPrompt("p"), "city", "Pa", Map("country" -> "FR"))
      _              <- client.complete(CompletionReference.ForPrompt("p"), "city", "Pa")
      requests       <- sent
    yield
      assertEquals(params(requests(0))("context"), Some(Json.obj("arguments" -> Json.obj("country" -> "FR".asJson))))
      assertEquals(params(requests(1))("context"), None)

  test("callToolGathering retries an input_required that asks nothing, then returns the result"):
    for
      (client, sent) <- scripted { r =>
                          if params(r).contains("requestState") then JsonRpcMessage.Success(r.id, CallToolResult.text("done").asJson)
                          else JsonRpcMessage.Success(r.id, InputRequiredResult(requestState = Some("s1")).asJson)
                        }
      result         <- client.callToolGathering("t")
      requests       <- sent
    yield
      assertEquals(result.content, List(ContentBlock.Text("done")))
      assertEquals(params(requests(1))("requestState"), Some("s1".asJson))

  test("answer: an undecodable request, bad params, an unknown method and a missing handler each fail clearly"):
    for
      (client, _) <- scripted(ok(Json.obj()))
      notARequest <- client.answer("k", Json.fromString("x")).attempt
      (eliciting, _) <- scripted(ok(Json.obj()), InputHandlers.elicitation[IO](_ => IO.pure(ElicitResult(ElicitAction.Cancel))))
      badParams   <- eliciting.answer("k", InputRequest(InputRequest.ElicitMethod, Json.fromString("x")).asJson).attempt
      unknown     <- client.answer("k", InputRequest("tasks/get", Json.obj()).asJson).attempt
      noHandler   <- client.answer("k", InputRequest.listRoots.asJson).attempt
    yield
      assert(notARequest.left.exists(_.isInstanceOf[McpClientError.Malformed]), notARequest.toString)
      assert(badParams.left.exists(_.isInstanceOf[McpClientError.Malformed]), badParams.toString)
      assertEquals(unknown.left.toOption, Some(McpClientError.UnsupportedInputRequest("k", "tasks/get")))
      assertEquals(noHandler.left.toOption, Some(McpClientError.UnsupportedInputRequest("k", "roots/list")))

  test("an HTTP error's message names the status, and the challenge when there is one"):
    assertEquals(McpClientError.Http(401, List("Bearer realm=\"x\""), "").getMessage, "HTTP 401 (Bearer realm=\"x\")")
    assertEquals(McpClientError.Http(503, Nil, "busy").getMessage, "HTTP 503")
