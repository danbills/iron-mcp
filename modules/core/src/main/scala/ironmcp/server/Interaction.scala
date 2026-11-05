package ironmcp
package server

import io.circe.{Decoder, Json, JsonObject, Printer}
import io.circe.syntax.*
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** What an interactive tool's handler returns: either it needs input from the
  * client first (`Left`, built with [[ToolContext.ask]]), or it is done.
  */
type Interaction[+O] = Either[InputRequiredResult, O]

object Interaction:
  def done[O](value: O): Interaction[O] = Right(value)

/** Protects `requestState` on its trip through the client.
  *
  * A stateless server keeps a multi-round tool's progress in `requestState`, and
  * the client hands it back on the retry. The client can change it on the way,
  * so a server that trusts it as-is can be talked into skipping a confirmation
  * or acting on details the user never approved. The codec seals the state
  * when it leaves and refuses it on the way back if it was altered or moved to
  * another call.
  */
trait RequestStateCodec:
  def seal(tool: String, arguments: JsonObject, state: String): String
  def open(tool: String, arguments: JsonObject, token: String): Option[String]

object RequestStateCodec:

  /** HMAC-SHA256 over the state, the tool name and the call's arguments, so a
    * sealed state cannot be edited, nor replayed on another tool or with other
    * arguments. Replicas behind a load balancer must share the key.
    *
    * The state itself is only encoded, not encrypted: do not put secrets in it.
    * It does not expire either; put a timestamp in the state and check it if
    * that matters for the tool.
    */
  def hmac(key: Array[Byte]): RequestStateCodec =
    require(key.length >= 32, "a requestState key needs at least 32 bytes")
    val secret = key.clone()
    new RequestStateCodec:
      private val encoder = Base64.getUrlEncoder.withoutPadding
      private val decoder = Base64.getUrlDecoder

      private def mac(tool: String, arguments: JsonObject, state: String): Array[Byte] =
        // Arguments are compared by content, not by the client's key order.
        val canonical = Json.fromJsonObject(arguments).printWith(Printer.noSpaces.copy(sortKeys = true))
        HmacSha256.hmac(secret, s"$tool\u0000$canonical\u0000$state".getBytes(UTF_8))

      def seal(tool: String, arguments: JsonObject, state: String): String =
        val payload = encoder.encodeToString(state.getBytes(UTF_8))
        s"v1.$payload.${encoder.encodeToString(mac(tool, arguments, state))}"

      def open(tool: String, arguments: JsonObject, token: String): Option[String] =
        token.split('.') match
          case Array("v1", payload, signature) =>
            for
              state <- scala.util.Try(String(decoder.decode(payload), UTF_8)).toOption
              received <- scala.util.Try(decoder.decode(signature)).toOption
              if HmacSha256.constantTimeEquals(received, mac(tool, arguments, state))
            yield state
          case _ => None

  /** Passes the state through untouched. Only for tests, or for a tool whose
    * state carries nothing a client could gain by changing.
    */
  val unprotected: RequestStateCodec = new RequestStateCodec:
    def seal(tool: String, arguments: JsonObject, state: String): String         = state
    def open(tool: String, arguments: JsonObject, token: String): Option[String] = Some(token)

/** What an interactive tool can see of the request beyond its arguments. */
final class ToolContext private[server] (val params: CallToolParams, val requestState: Option[String]):

  def clientCapabilities: ClientCapabilities = params._meta.clientCapabilities

  /** The client's answer to the input request sent under `key`, if it sent one. */
  def response(key: String): Option[ElicitResult] =
    params.inputResponses.flatMap(_.get(key)).flatMap(_.as[ElicitResult].toOption)

  /** The accepted form content under `key`, decoded into `R`. The content is
    * user input, so it is validated like tool arguments: every refinement on
    * `R` must hold, or this is `None`.
    */
  def accepted[R: Decoder](key: String): Option[R] =
    response(key)
      .filter(_.action == ElicitAction.Accept)
      .flatMap(_.content)
      .flatMap(content => Json.fromJsonObject(content).as[R].toOption)

  /** Ends this round asking the client for input; `state` comes back on the retry. */
  def ask(state: String)(requests: (String, InputRequest)*): Interaction[Nothing] =
    Left(InputRequiredResult(inputRequests = Some(requests.map((key, request) => key -> request.asJson).toMap), requestState = Some(state)))

object Elicit:

  /** A form whose fields are `R`'s: the requested schema is derived from it,
    * and [[ToolContext.accepted]] decodes the answer back into it.
    */
  def form[R](message: String)(using schema: JsonSchemaOf[R]): InputRequest =
    InputRequest.elicit(ElicitFormParams(message, schema.schema.as[ObjectSchema].getOrElse(ObjectSchema.empty)))
