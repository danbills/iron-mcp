package ironmcp
package protocol

import io.circe.*
import io.github.iltotore.iron.circe.given

/** A filesystem boundary the client exposes, from `roots/list`. `uri` is a
  * `file://` URI by the spec.
  */
final case class Root(uri: Uri, name: Option[String] = None) derives Codec.AsObject

final case class ListRootsResult(roots: List[Root]) derives Codec.AsObject
