package ironmcp

import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import io.github.iltotore.iron.autoRefine
import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper}
import io.modelcontextprotocol.server.{McpServer, McpStatelessAsyncServer}
import io.modelcontextprotocol.spec.McpSchema
import org.http4s.HttpRoutes

import scala.jdk.CollectionConverters.*

/** Who the server says it is during `initialize`. */
final case class ServerInfo(name: NonEmptyString, version: SemVer)

/** A stateless MCP server, assembled from Iron-typed tools and exposed as
  * plain http4s routes you can mount wherever you like.
  */
final case class IronMcpServer(
    underlying: McpStatelessAsyncServer,
    routes: HttpRoutes[IO]
)

object IronMcpServer:

  /** The SDK's own mapper. We do not replace it with Circe: it deserializes
    * Java records reflectively (the whole `McpSchema` surface), which Circe
    * cannot do generically. Circe owns our side of the boundary — tool
    * arguments and results — and Jackson owns the protocol envelope.
    */
  val defaultMapper: McpJsonMapper = McpJsonDefaults.getMapper

  def resource(
      info: ServerInfo,
      tools: List[ToolDef[?]],
      endpoint: EndpointPath = "/mcp",
      instructions: Option[NonEmptyString] = None,
      allowedOrigins: Set[String] = Set.empty,
      mapper: McpJsonMapper = defaultMapper
  ): Resource[IO, IronMcpServer] =
    for
      dispatcher <- Dispatcher.parallel[IO]
      transport   = Http4sStatelessTransport(endpoint, mapper, allowedOrigins)
      server     <- Resource.make(IO(build(info, tools, instructions, transport, mapper, dispatcher)))(s =>
                      IO(s.closeGracefully()).attempt.void
                    )
    yield IronMcpServer(server, transport.routes)

  private def build(
      info: ServerInfo,
      tools: List[ToolDef[?]],
      instructions: Option[NonEmptyString],
      transport: Http4sStatelessTransport,
      mapper: McpJsonMapper,
      dispatcher: Dispatcher[IO]
  ): McpStatelessAsyncServer =
    val specification = McpServer
      .async(transport)
      .serverInfo(info.name, info.version)
      .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
      // The SDK validates arguments against each tool's declared JSON Schema
      // before our decoder runs. Both layers are wanted: the schema is what the
      // model was told, the Iron types are what our code actually requires.
      .validateToolInputs(true)
    instructions.foreach(i => specification.instructions(i))
    specification.tools(tools.map(_.toSpecification(mapper, dispatcher)).asJava)
    specification.build()
