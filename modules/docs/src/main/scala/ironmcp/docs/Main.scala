package ironmcp.docs

import java.nio.file.Paths

object Main:
  def main(args: Array[String]): Unit =
    val cp = System.getProperty("java.class.path")
    val defaultArgs = Array(
      "--classpath", cp,
      "--in", "docs/in",
      "--out", "docs/out",
      "--no-link-hygiene"
    )
    val effectiveArgs = if args.isEmpty then defaultArgs else args
    val exit = mdoc.Main.process(effectiveArgs, System.out, Paths.get("."))
    if exit != 0 then sys.error(s"mdoc failed with exit code $exit")
