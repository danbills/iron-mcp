import java.io.{File, FileOutputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.sys.process.*

/** MCP Bundles (`.mcpb`, Handbook §5.5): a zip with a `manifest.json` and the
  * server, which a host such as Claude Desktop installs in one step.
  *
  * Writes manifest_version 0.3, the revision the handbook validates with
  * `@anthropic-ai/mcpb` 2.1.2. A Scala Native binary is a `binary` server: no
  * runtime to declare, one file to ship.
  *
  * The build has no JSON library, so the manifest is written by hand; every
  * value goes through `str`.
  */
object Mcpb {

  final case class Tool(name: String, description: String)

  final case class Manifest(
      name: String,
      displayName: String,
      version: String,
      description: String,
      author: String,
      license: String,
      homepage: Option[String],
      binaryName: String,
      tools: Seq[Tool]
  )

  /** `0.4.0+11-84cef42d+20261105-1756` (sbt-dynver between tags) is not
    * semver; the manifest gets the release part.
    */
  def semver(version: String): String = version.takeWhile(_ != '+')

  /** Node's `process.platform` name for the build host: what `platforms` uses. */
  def hostPlatform: String = {
    val os = System.getProperty("os.name").toLowerCase
    if (os.contains("mac")) "darwin" else if (os.contains("win")) "win32" else "linux"
  }

  private def str(value: String): String =
    "\"" + value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""

  def json(m: Manifest, platform: String): String = {
    val entry = s"server/${m.binaryName}"
    val tools = m.tools.map(t => s"""    { "name": ${str(t.name)}, "description": ${str(t.description)} }""").mkString(",\n")
    s"""{
       |  "manifest_version": "0.3",
       |  "name": ${str(m.name)},
       |  "display_name": ${str(m.displayName)},
       |  "version": ${str(semver(m.version))},
       |  "description": ${str(m.description)},
       |  "author": { "name": ${str(m.author)} },
       |  "license": ${str(m.license)},${m.homepage.fold("")(h => s"\n  \"homepage\": ${str(h)},")}
       |  "server": {
       |    "type": "binary",
       |    "entry_point": ${str(entry)},
       |    "mcp_config": {
       |      "command": ${str("${__dirname}/" + entry)},
       |      "args": []
       |    }
       |  },
       |  "tools": [
       |$tools
       |  ],
       |  "compatibility": {
       |    "platforms": [${str(platform)}]
       |  }
       |}
       |""".stripMargin
  }

  /** Asks the linked server for its tools over stdio, so the manifest cannot
    * list tools the binary does not serve (or miss one it does).
    */
  def servedToolNames(binary: Path): Set[String] = {
    val meta    = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""
    val request = s"""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{$meta}}""" + "\n"
    val output  = new StringBuilder
    val exit = Process(binary.toString).#<(new java.io.ByteArrayInputStream(request.getBytes("UTF-8"))).!(ProcessLogger(line => output.append(line).append('\n'), _ => ()))
    if (exit != 0) sys.error(s"$binary exited with $exit while listing its tools")
    // A tool's name is the only "name" with a string value in a tools/list
    // reply: properties are object keys, not "name" fields.
    "\"name\":\"([a-zA-Z0-9_-]+)\"".r.findAllMatchIn(output.toString).map(_.group(1)).toSet
  }

  /** Writes `<dir>/manifest.json` and `<dir>/server/<binary>`, and zips them into `out`. */
  def pack(manifest: Manifest, binary: Path, dir: File, out: File): File = {
    val served   = servedToolNames(binary)
    val declared = manifest.tools.map(_.name).toSet
    if (served != declared)
      sys.error(s"the manifest lists tools ${declared.toList.sorted.mkString(", ")} but the binary serves ${served.toList.sorted.mkString(", ")}")

    sbt.IO.delete(dir)
    val serverDir = new File(dir, "server")
    serverDir.mkdirs()
    val target = new File(serverDir, manifest.binaryName)
    Files.copy(binary, target.toPath)
    target.setExecutable(true)
    sbt.IO.write(new File(dir, "manifest.json"), json(manifest, hostPlatform))

    out.getParentFile.mkdirs()
    val entries = Seq("manifest.json" -> (new File(dir, "manifest.json"), 0x81a4), s"server/${manifest.binaryName}" -> (target, 0x81ed))
    val zip = new ZipOutputStream(new FileOutputStream(out))
    try
      for ((name, (file, _)) <- entries) {
        zip.putNextEntry(new ZipEntry(name))
        zip.write(Files.readAllBytes(file.toPath))
        zip.closeEntry()
      }
    finally zip.close()
    withUnixModes(out, entries.map { case (name, (_, mode)) => name -> mode }.toMap)
    out
  }

  /** java.util.zip records no permissions, so a host would unpack the binary
    * without its executable bit. This rewrites each central directory record
    * as "made by Unix" with a full `st_mode` (file type and permissions) in the
    * high half of its external attributes, which is what `mcpb pack` and
    * `unzip` read. (The JDK's zip filesystem can store permissions, but without
    * the file type bits, and `mcpb unpack` then mistakes entries for directories.)
    */
  private def withUnixModes(zip: File, modes: Map[String, Int]): Unit = {
    val bytes = Files.readAllBytes(zip.toPath)
    val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    // The end of central directory record: no archive comment, so it is the last 22 bytes.
    val eocd = bytes.length - 22
    if (buf.getInt(eocd) != 0x06054b50) sys.error(s"$zip: no end of central directory record")
    val count = buf.getShort(eocd + 10) & 0xffff
    var at    = buf.getInt(eocd + 16)
    for (_ <- 0 until count) {
      if (buf.getInt(at) != 0x02014b50) sys.error(s"$zip: bad central directory record at $at")
      val nameLength    = buf.getShort(at + 28) & 0xffff
      val extraLength   = buf.getShort(at + 30) & 0xffff
      val commentLength = buf.getShort(at + 32) & 0xffff
      val name          = new String(bytes, at + 46, nameLength, "UTF-8")
      modes.get(name).foreach { mode =>
        buf.putShort(at + 4, ((3 << 8) | 20).toShort) // version made by: Unix, spec 2.0
        buf.putInt(at + 38, mode << 16)
      }
      at += 46 + nameLength + extraLength + commentLength
    }
    Files.write(zip.toPath, bytes)
  }
}
