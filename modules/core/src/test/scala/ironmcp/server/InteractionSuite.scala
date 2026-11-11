package ironmcp
package server

import io.circe.JsonObject
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*

/** RequestStateCodec's refusals, ToolContext's view of the request, and the input-request builders. */
class InteractionSuite extends munit.FunSuite:

  private val key   = Array.tabulate[Byte](32)(_.toByte)
  private val codec = RequestStateCodec.hmac(key)
  private val args  = JsonObject("city" -> "Paris".asJson)

  test("a key shorter than 32 bytes is refused"):
    intercept[IllegalArgumentException](RequestStateCodec.hmac(Array.fill[Byte](31)(1)))

  test("a sealed state opens for the same tool and arguments, in any key order"):
    val sealed_ = codec.seal("weather", JsonObject("a" -> 1.asJson, "b" -> 2.asJson), "step-2")
    assertEquals(codec.open("weather", JsonObject("b" -> 2.asJson, "a" -> 1.asJson), sealed_), Some("step-2"))

  test("a token that is not v1.<payload>.<mac> is refused"):
    List("step-2", "v2.a.b", "v1.a", "v1.a.b.c", "").foreach(t => assertEquals(codec.open("weather", args, t), None, t))

  test("an altered payload, a forged mac, undecodable base64, another tool or other arguments are refused"):
    val token = codec.seal("weather", args, "step-2")
    val Array(v, payload, mac) = token.split('.'): @unchecked
    val forged = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString("step-9".getBytes)
    assertEquals(codec.open("weather", args, s"$v.$forged.$mac"), None)
    assertEquals(codec.open("weather", args, s"$v.$payload.${mac.reverse}"), None)
    assertEquals(codec.open("weather", args, s"$v.***.$mac"), None)
    assertEquals(codec.open("weather", args, s"$v.$payload.***"), None)
    assertEquals(codec.open("news", args, token), None)
    assertEquals(codec.open("weather", JsonObject("city" -> "Rome".asJson), token), None)

  test("a different key cannot open the state"):
    val other = RequestStateCodec.hmac(Array.fill[Byte](32)(7))
    assertEquals(other.open("weather", args, codec.seal("weather", args, "s")), None)

  test("unprotected passes the state through both ways"):
    assertEquals(RequestStateCodec.unprotected.seal("t", args, "s"), "s")
    assertEquals(RequestStateCodec.unprotected.open("t", args, "s"), Some("s"))

  test("ToolContext shows the request's client capabilities"):
    val caps = ClientCapabilities(roots = Some(JsonObject.empty))
    val ctx  = ToolContext(CallToolParams("t", RequestMeta(LatestProtocolVersion, caps)), None)
    assertEquals(ctx.clientCapabilities, caps)

  test("Sample(params) is a sampling request carrying the params as given"):
    val params = CreateMessageParams(List(SamplingMessage(Role.user, ContentBlock.Text("hi"))), maxTokens = 5)
    val sample = Sample(params)
    assertEquals(sample.method: String, "sampling/createMessage")
    assertEquals(sample.params.as[CreateMessageParams], Right(params))
