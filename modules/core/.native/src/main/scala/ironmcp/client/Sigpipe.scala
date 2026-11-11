package ironmcp
package client

import scala.scalanative.libc.signal.{signal, SIG_IGN}
import scala.scalanative.posix.signal.SIGPIPE

/** A write to a child that has exited raises SIGPIPE, whose default action ends the whole process. The JVM ignores
  * it; a native binary must too, so the write fails with EPIPE instead and the client sees a closed connection.
  */
private[client] object Sigpipe:
  def ignore(): Unit =
    val _ = signal(SIGPIPE, SIG_IGN)
