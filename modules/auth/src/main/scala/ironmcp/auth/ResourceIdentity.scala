package ironmcp
package auth

import java.net.URI
import scala.util.Try

/** The one name of a protected MCP endpoint: its public URL, as clients reach
  * it (behind a proxy, the proxy's URL).
  *
  * Everything that must agree comes from it, so nothing can drift:
  *   - the `resource` of the RFC 9728 metadata document;
  *   - the audience a token must carry (RFC 8707);
  *   - the `resource_metadata` URL in challenges, with the well-known segment
  *     inserted before the path as RFC 9728 §3.1 says
  *     (`https://host/.well-known/oauth-protected-resource/mcp`);
  *   - the path the MCP routes are mounted on.
  *
  * The URL must be `https`; plain `http` is allowed only for loopback hosts,
  * for development. It has no query or fragment, and its scheme and host are
  * lowercased.
  */
final case class ResourceIdentity private (origin: String, path: String):

  /** The canonical resource URI: what tokens must name in `aud`. */
  val resource: String = origin + path

  /** Where the RFC 9728 document is served, and what challenges point at. */
  val metadataUrl: String = origin + metadataPath

  /** The metadata document's path on this server. */
  def metadataPath: String = ResourceIdentity.WellKnown + (if path == "/" then "" else path)

  override def toString: String = resource

object ResourceIdentity:
  val WellKnown = "/.well-known/oauth-protected-resource"

  /** Parses a public endpoint URL, refusing anything that is not a canonical
    * `https` URL (or loopback `http`).
    */
  def from(url: String): Either[String, ResourceIdentity] =
    for
      uri <- Try(new URI(url)).toEither.left.map(_ => s"'$url' is not a URI")
      _   <- requireSecure(url)
      _   <- Either.cond(uri.getRawQuery == null, (), "a resource URL has no query")
      _   <- Either.cond(uri.getRawFragment == null, (), "a resource URL has no fragment")
      _   <- Either.cond(uri.getRawUserInfo == null, (), "a resource URL has no user info")
      host = uri.getHost.toLowerCase
      scheme = uri.getScheme.toLowerCase
      port = uri.getPort match
        case -1                              => ""
        case 443 if scheme == "https"        => ""
        case 80 if scheme == "http"          => ""
        case p                               => s":$p"
      rawPath = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
      path = if rawPath.length > 1 then rawPath.stripSuffix("/") else rawPath
    yield ResourceIdentity(s"$scheme://$host$port", path)

  /** As [[from]], throwing on a bad URL: for configuration known at startup. */
  def unsafe(url: String): ResourceIdentity =
    from(url).fold(reason => throw new IllegalArgumentException(reason), identity)

  private val loopbackHosts = Set("localhost", "127.0.0.1", "::1", "[::1]")

  def isLoopback(host: String): Boolean =
    val h = host.toLowerCase
    loopbackHosts.contains(h) || h.endsWith(".localhost")

  /** `https`, or `http` to a loopback host. Every URL a server or client
    * fetches keys, metadata or tokens from goes through this.
    */
  def requireSecure(url: String): Either[String, Unit] =
    Try(new URI(url)).toOption.filter(u => u.getScheme != null && u.getHost != null) match
      case None => Left(s"'$url' is not an absolute URL")
      case Some(u) =>
        u.getScheme.toLowerCase match
          case "https"                       => Right(())
          case "http" if isLoopback(u.getHost) => Right(())
          case other                         => Left(s"'$url' must be https (got $other, and the host is not loopback)")
