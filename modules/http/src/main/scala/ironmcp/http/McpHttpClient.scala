package ironmcp
package http

import cats.effect.{Async, Concurrent, Resource}
import cats.syntax.all.*
import fs2.io.net.Network
import io.circe.Json
import ironmcp.client.{ClientTransport, McpClientError}
import ironmcp.protocol.{JsonRpcMessage, LatestProtocolVersion, ReservedMeta}
import ironmcp.transport.Wire
import org.http4s.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.{`Content-Type`, `WWW-Authenticate`}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

/** The client side of Streamable HTTP: one POST per message to the MCP
  * endpoint, with the headers the binding mirrors from the body.
  *
  * A request is answered by a JSON body or by an SSE stream scoped to it; in
  * the stream, notifications go to `onNotification` until the response with
  * the request's id arrives. A 4xx carrying a JSON-RPC error (an unsupported
  * version, an unknown method, a header mismatch) is that error. Any other
  * answer that is not 2xx is [[McpClientError.Http]], with its
  * `WWW-Authenticate` challenges, which is what a client needs to
  * re-authorize; [[challenges]] parses them.
  *
  * `headers` is evaluated for every request, so credentials can change between
  * calls (a step-up after `insufficient_scope`, a refreshed token).
  */
object McpHttpClient:

  def transport[F[_]](
      client: Client[F],
      endpoint: Uri,
      headers: F[Headers],
      onNotification: JsonRpcMessage.Notification => F[Unit]
  )(using F: Concurrent[F]): ClientTransport[F] = new ClientTransport[F]:

    private def post(message: JsonRpcMessage, method: String, params: Option[Json]): F[Request[F]] =
      headers.map { extra =>
        val version = params
          .flatMap(_.hcursor.downField("_meta").get[String](ReservedMeta.ProtocolVersion).toOption)
          .getOrElse(LatestProtocolVersion: String)
        val name = McpHeaders.nameParam(method).flatMap(field => params.flatMap(_.hcursor.get[String](field).toOption))
        Request[F](Method.POST, endpoint)
          .withEntity(Wire.encode(message))
          .withContentType(`Content-Type`(MediaType.application.json))
          .putHeaders(
            Header.Raw(CIString("Accept"), "application/json, text/event-stream"),
            Header.Raw(McpHeaders.ProtocolVersion, version),
            Header.Raw(McpHeaders.Method, method)
          )
          .putHeaders(name.map(n => Header.Raw(McpHeaders.Name, McpHeaders.encode(n))).toList)
          .putHeaders(extra.headers)
      }

    private def rejected(response: Response[F], body: String): McpClientError =
      McpClientError.Http(
        response.status.code,
        response.headers.get(CIString("WWW-Authenticate")).fold(Nil)(_.toList.map(_.value)),
        body
      )

    def request(message: JsonRpcMessage.Request): F[JsonRpcMessage] =
      post(message, message.method, message.params).flatMap { req =>
        client.run(req).use { response =>
          val isStream = response.contentType.exists(_.mediaType.satisfies(MediaType.`text/event-stream`))
          if response.status.isSuccess && isStream then
            response.body
              .through(ServerSentEvent.decoder[F])
              .evalMapFilter { event =>
                event.data.flatMap(data => Wire.decode(data).toOption) match
                  case Some(n: JsonRpcMessage.Notification) => onNotification(n).as(None)
                  case Some(reply @ JsonRpcMessage.Success(id, _)) if id == message.id       => F.pure(Some(reply))
                  case Some(reply @ JsonRpcMessage.Failure(Some(id), _)) if id == message.id => F.pure(Some(reply))
                  case _                                                                       => F.pure(None)
              }
              .head
              .compile
              .last
              .flatMap(F.fromOption(_, McpClientError.Closed(s"the event stream ended without a response to ${message.method: String}")))
          else
            response.bodyText.compile.string.flatMap { body =>
              Wire.decode(body) match
                case Right(reply @ (JsonRpcMessage.Success(_, _) | JsonRpcMessage.Failure(_, _))) if response.status.isSuccess =>
                  F.pure(reply)
                // A modern server answers a rejected request with a JSON-RPC error
                // body (400 -32020/-32022, 404 -32601): that is the answer.
                case Right(reply @ JsonRpcMessage.Failure(_, _)) => F.pure(reply)
                case _ =>
                  if response.status.isSuccess then F.raiseError(McpClientError.Malformed(s"${message.method: String}: no JSON-RPC response in the body"))
                  else F.raiseError(rejected(response, body))
            }
        }
      }

    def notify(message: JsonRpcMessage.Notification): F[Unit] =
      post(message, message.method, message.params).flatMap { req =>
        client.run(req).use { response =>
          if response.status.isSuccess then F.unit
          else response.bodyText.compile.string.flatMap(body => F.raiseError(rejected(response, body)))
        }
      }

  def transport[F[_]: Concurrent](client: Client[F], endpoint: Uri): ClientTransport[F] =
    transport(client, endpoint, Concurrent[F].pure(Headers.empty), _ => Concurrent[F].unit)

  /** An ember client for `endpoint`, for callers that do not have an http4s
    * client already. `logger` gets Ember's own log; `None` discards it.
    */
  def ember[F[_]: {Async, Network}](
      endpoint: Uri,
      headers: Option[F[Headers]] = None,
      logger: Option[Logger[F]] = None
  ): Resource[F, ClientTransport[F]] =
    EmberClientBuilder.default[F].withLogger(logger.getOrElse(NoOpLogger[F])).build.map(client =>
      transport(client, endpoint, headers.getOrElse(Async[F].pure(Headers.empty)), _ => Async[F].unit)
    )

  /** The challenges of a rejected request (RFC 6750 for Bearer: `error`,
    * `scope`, `resource_metadata`, `error_description` are its params).
    */
  def challenges(error: McpClientError.Http): List[Challenge] =
    error.wwwAuthenticate.flatMap(value => `WWW-Authenticate`.parse(value).toOption.toList.flatMap(_.values.toList))
