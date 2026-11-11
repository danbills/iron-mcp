package ironmcp
package client

/** The JVM already ignores SIGPIPE: a write to a child that has exited fails with an IOException. (Scala Native has
  * its own Sigpipe, which ignores it.)
  */
private[client] object Sigpipe:
  def ignore(): Unit = ()
