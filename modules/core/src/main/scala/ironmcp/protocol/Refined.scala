package ironmcp
package protocol

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

/** Constraints taken directly from the MCP specification. Where the spec states
  * a rule in prose, it is stated here as a type, so violating it is a
  * compile error for literals and a decode error for wire data.
  */

type NonEmptyString = String :| Not[Empty]

/** Protocol revisions are dates: `YYYY-MM-DD`. */
type ProtocolVersionC = Match["^[0-9]{4}-[0-9]{2}-[0-9]{2}$"]
type ProtocolVersion  = String :| ProtocolVersionC

/** Tool names as every host enforces them. */
type ToolNameC = Match["^[a-zA-Z0-9_-]{1,128}$"]
type ToolName  = String :| ToolNameC

/** `_meta` keys, spelled out by the spec:
  *
  *   - optional prefix: dot-separated labels then a slash; each label starts
  *     with a letter, ends with a letter or digit, interior may hyphenate
  *   - name: starts and ends alphanumeric; interior may add `-`, `_`, `.`
  */
type MetaKeyC = Match[
  "^(?:[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*/)?(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)?$"
]
type MetaKey = String :| MetaKeyC

/** Pagination cursors are opaque to clients but never empty on the wire. */
type Cursor = String :| Not[Empty]

type Uri      = String :| Not[Empty]
type MimeType = String :| Match["^[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+$"]

/** JSON-RPC forbids fractional ids in practice; MCP allows string or number. */
type IdNumber = Long

/** Base64 payloads for binary content blocks. */
type Base64 = String :| Match["^[A-Za-z0-9+/]*={0,2}$"]

/** Progress is monotonically increasing per the spec. */
type Progress = Double :| GreaterEqual[0.0]

type Port = Int :| Interval.Closed[1, 65535]

type EndpointPath = String :| (StartWith["/"] & Not[Empty])
