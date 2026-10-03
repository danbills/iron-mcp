package ironmcp
package nasa

import cats.effect.IO
import cats.~>
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.protocol.*
import ironmcp.server.ToolSet
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Response, Status}
import org.http4s.circe.*
import org.http4s.client.Client

class ApodToolsSuite extends CatsEffectSuite:

  type Result[A] = Either[Throwable, A]

  private val meta = RequestMeta(protocolVersion = LatestProtocolVersion, clientCapabilities = ClientCapabilities.none)

  private val picture = Picture(
    date = Some("2026-09-29"),
    title = Some("Pillars"),
    mediaType = Some("image"),
    url = Some("https://apod.test/p.jpg"),
    hdurl = Some("https://apod.test/p_hd.jpg"),
    thumbnailUrl = None,
    copyright = Some("\nA. Astronomer\n"),
    explanation = Some("Gas and dust.")
  )

  private def call[F[_]](tools: ToolSet[F], args: (String, Json)*): F[CallToolResult | InputRequiredResult] =
    tools.call(CallToolParams("get_apod", meta, Some(JsonObject(args*))))

  private def text(result: CallToolResult | InputRequiredResult): String = result match
    case r: CallToolResult => r.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString
    case other             => fail(s"unexpected: $other")

  test("get_apod renders the picture, with no IO and no network"):
    val canned = new (ApodOp ~> Result):
      def apply[A](op: ApodOp[A]): Result[A] = op match
        case ApodOp.PictureOf(date) =>
          assertEquals(date.map(d => d: String), Some("2026-09-29"))
          Right(picture)
    val result = call(ApodTools(canned), "date" -> "2026-09-29".asJson).toOption.get
    assertEquals(
      text(result),
      "APOD 2026-09-29 — Pillars\nMedia: image\nURL: https://apod.test/p.jpg\nHD:  https://apod.test/p_hd.jpg\nCredit: A. Astronomer\n\nGas and dust."
    )

  test("a malformed date never reaches the API"):
    val unreachable = new (ApodOp ~> Result):
      def apply[A](op: ApodOp[A]): Result[A] = fail(s"called with $op")
    val result = call(ApodTools(unreachable), "date" -> "yesterday".asJson).toOption.get
    assertEquals(result.asInstanceOf[CallToolResult].isError, Some(true))

  private def nasa(respond: org.http4s.Request[IO] => Response[IO]) =
    Client.fromHttpApp(HttpApp[IO](req => IO.pure(respond(req))))

  test("no key: the tool says how to get one"):
    call(ApodTools(ApodHttp4s(nasa(_ => fail("no request without a key")), apiKey = None))).map { result =>
      assertEquals(text(result), "get_apod failed: NASA_API_KEY is not set. Get a free APOD API key at https://api.nasa.gov and export NASA_API_KEY.")
    }

  test("ApodHttp4s sends key, thumbs and date, and decodes the picture"):
    val wire = Json.obj(
      "date" -> "2026-09-29".asJson, "title" -> "Pillars".asJson, "media_type" -> "video".asJson,
      "url" -> "https://apod.test/v".asJson, "thumbnail_url" -> "https://apod.test/t.jpg".asJson,
      "explanation" -> "Moving.".asJson, "service_version" -> "v1".asJson
    )
    val client = nasa { req =>
      assertEquals(req.uri.query.params, Map("api_key" -> "k3y", "thumbs" -> "true", "date" -> "2026-09-29"))
      Response[IO](Status.Ok).withEntity(wire)
    }
    ApodHttp4s(client, Some("k3y"))(ApodOp.PictureOf(Some("2026-09-29"))).map { p =>
      assertEquals((p.mediaType, p.thumbnailUrl, p.hdurl), (Some("video"), Some("https://apod.test/t.jpg"), None))
    }

  test("API errors surface their message, never the key"):
    val badDate  = Json.obj("code" -> 400.asJson, "msg" -> "Date must be between Jun 16, 1995 and today.".asJson)
    val badKey   = Json.obj("error" -> Json.obj("code" -> "API_KEY_INVALID".asJson, "message" -> "An invalid api_key was supplied.".asJson))
    for
      dateErr <- call(ApodTools(ApodHttp4s(nasa(_ => Response[IO](Status.BadRequest).withEntity(badDate)), Some("s3cret"))))
      keyErr  <- call(ApodTools(ApodHttp4s(nasa(_ => Response[IO](Status.Forbidden).withEntity(badKey)), Some("s3cret"))))
    yield
      assertEquals(text(dateErr), "get_apod failed: APOD API error 400: Date must be between Jun 16, 1995 and today.")
      assertEquals(text(keyErr), "get_apod failed: APOD API error 403: An invalid api_key was supplied.")
      assert(!text(dateErr).contains("s3cret") && !text(keyErr).contains("s3cret"))
