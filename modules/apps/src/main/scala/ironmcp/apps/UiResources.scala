package ironmcp
package apps

import cats.ApplicativeThrow
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.protocol.*
import ironmcp.server.{McpException, ResourceProvider}

/** One View: a self-contained HTML document. Inline its scripts and styles,
  * since the default sandbox loads nothing from the network (see [[UiCsp]]).
  */
final case class UiResource(
    uri: UiUri,
    name: NonEmptyString,
    html: String,
    description: Option[NonEmptyString] = None,
    meta: UiResourceMeta = UiResourceMeta()
)

/** A [[ResourceProvider]] serving Views. Each is listed and read with the
  * Apps media type and its `_meta.ui`; any other URI is `-32002`.
  *
  * Views are the same for every caller, so results are publicly cacheable.
  */
final class UiResources[F[_]](views: List[UiResource], ttlMs: CacheTtlMs = 300000L)(using F: ApplicativeThrow[F])
    extends ResourceProvider[F]:

  private val byUri: Map[String, UiResource] = views.map(view => (view.uri: String) -> view).toMap

  private def metaOf(view: UiResource): Option[MetaObject] =
    val ui = view.meta.asJson.deepDropNullValues
    Option.when(ui.asObject.exists(_.nonEmpty))(Map(McpApps.UiKey -> ui))

  def list(params: ListResourcesParams): F[ListResourcesResult] =
    F.pure(
      ListResourcesResult(
        views.map(view =>
          Resource(
            uri = (view.uri: String).assume,
            name = view.name,
            description = view.description,
            mimeType = Some(McpApps.MimeType),
            _meta = metaOf(view)
          )
        ),
        ttlMs,
        CacheScope.`public`
      )
    )

  def read(params: ReadResourceParams): F[ReadResourceResult | InputRequiredResult] =
    byUri.get(params.uri) match
      case Some(view) =>
        F.pure(
          ReadResourceResult(
            List(ResourceContents.Text(params.uri, view.html, Some(McpApps.MimeType), metaOf(view))),
            ttlMs,
            CacheScope.`public`
          )
        )
      case None => F.raiseError(McpException.resourceNotFound(params.uri))

object UiResources:
  def of[F[_]: ApplicativeThrow](views: UiResource*): UiResources[F] = UiResources(views.toList)
