package ironmcp

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

/** Every string that crosses the MCP boundary is refined. Nothing in this
  * library takes a bare `String` where the protocol constrains the value.
  */

/** Tool names as hosts actually enforce them: `^[a-zA-Z0-9_-]{1,128}$`.
  * The MCP schema itself only says "string", but Claude, omp and every other
  * host reject names outside this shape, so we reject them at compile time.
  */
type ToolNameC = Match["^[a-zA-Z0-9_-]{1,128}$"]
type ToolName  = String :| ToolNameC

type NonEmptyString = String :| Not[Empty]

/** Server versions are advertised in `initialize`; keep them parseable. */
type SemVerC = Match["^[0-9]+\\.[0-9]+\\.[0-9]+([-+][0-9A-Za-z.-]+)?$"]
type SemVer  = String :| SemVerC

type Port = Int :| Interval.Closed[1, 65535]

/** The single HTTP endpoint a stateless server exposes, e.g. "/mcp". */
type EndpointPathC = StartWith["/"] & Not[Empty]
type EndpointPath  = String :| EndpointPathC
