package ironmcp
package eventlog

import cats.effect.{Ref, Resource, Sync}
import cats.syntax.all.*
import io.circe.{Codec, Decoder, Encoder, Printer, parser}
import io.circe.syntax.*

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{FileSystems, Files, Path, StandardCopyOption, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** An [[EventJournal]] in a directory: events appended to JSON-lines segments, and snapshots of the whole state.
  *
  * {{{
  * snapshot-00000000000000004096.json   {"seq":4096,"state":{...}}      the state after event 4096
  * events-00000000000000004097.jsonl    {"seq":4097,"event":{...}} ...  events from 4097 on
  * }}}
  *
  * Opening loads the latest snapshot and replays the events after it. A last line cut short by a crash is dropped
  * (its batch was never answered); anything else unreadable fails the open rather than lose state quietly. Each
  * open writes a new segment, so nothing is ever appended after a torn line.
  *
  * Every `snapshotEvery` events, and on release, the state is snapshotted (written aside, synced, renamed into
  * place) and the files it covers are deleted. With `fsync`, each batch is on disk before it is answered.
  * Files and directory are owner-only where the file system has POSIX permissions: what a log keeps is often
  * private.
  */
object FileEventJournal:

  def open[F[_], E, S](dir: Path, snapshotEvery: Int = 4096, fsync: Boolean = true)(using
      F: Sync[F],
      evolve: Evolve[S, E],
      eventsOut: Encoder[E],
      eventsIn: Decoder[E],
      statesOut: Encoder[S],
      statesIn: Decoder[S]
  ): Resource[F, EventJournal[F, E, S]] =
    Resource
      .make(F.blocking(Disk[E, S](dir, fsync)).flatMap(disk => F.blocking(disk.load()).map((disk, _))))((disk, _) =>
        F.blocking(disk.close())
      )
      .flatMap { (disk, loaded) =>
        Resource
          .make(F.blocking(disk.startSegment(loaded.seq + 1)) *> Ref.of[F, Position[S]](Position(loaded.seq, 0, loaded.state)))(
            position => position.get.flatMap(p => F.blocking(if p.sinceSnapshot > 0 then disk.snapshot(p.seq, p.state)))
          )
          .map { position =>
            new EventJournal[F, E, S]:
              val recovered: S = loaded.state
              def append(events: List[E], after: S): F[Unit] =
                position.get.flatMap { p =>
                  val seq = p.seq + events.size
                  F.blocking(disk.write(p.seq + 1, events)) *> {
                    if p.sinceSnapshot + events.size >= snapshotEvery then
                      F.blocking(disk.snapshot(seq, after)) *> position.set(Position(seq, 0, after))
                    else position.set(Position(seq, p.sinceSnapshot + events.size, after))
                  }
                }
          }
      }

  /** The last event written, how many since the last snapshot, and the state they lead to. */
  private final case class Position[S](seq: Long, sinceSnapshot: Int, state: S)

  private final case class Loaded[S](seq: Long, state: S)

  private final case class Snapshot[S](seq: Long, state: S)
  private final case class Entry[E](seq: Long, event: E)

  private val printer = Printer.noSpaces

  /** The blocking file work. Used by one writer at a time: the log's processor. */
  private final class Disk[E, S](dir: Path, fsync: Boolean)(using
      evolve: Evolve[S, E],
      eventsOut: Encoder[E],
      eventsIn: Decoder[E],
      statesOut: Encoder[S],
      statesIn: Decoder[S]
  ):
    private given Codec.AsObject[Snapshot[S]] = Codec.AsObject.derived
    private given Codec.AsObject[Entry[E]]    = Codec.AsObject.derived

    private val posix = FileSystems.getDefault.supportedFileAttributeViews.contains("posix")
    @volatile private var segment: Option[FileChannel] = None

    if posix then
      if !Files.exists(dir) then Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))): Unit
    else Files.createDirectories(dir): Unit

    private def name(prefix: String, seq: Long, suffix: String) = f"$prefix-$seq%020d$suffix"
    private def seqOf(file: Path, prefix: String, suffix: String): Option[Long] =
      val n = file.getFileName.toString
      Option.when(n.startsWith(prefix + "-") && n.endsWith(suffix))(n.drop(prefix.length + 1).dropRight(suffix.length)).flatMap(_.toLongOption)

    private def listed(prefix: String, suffix: String): List[(Long, Path)] =
      val stream = Files.list(dir)
      try stream.iterator.asScala.flatMap(p => seqOf(p, prefix, suffix).map(_ -> p)).toList.sortBy(_._1)
      finally stream.close()

    private def snapshots = listed("snapshot", ".json")
    private def segments  = listed("events", ".jsonl")

    def load(): Loaded[S] =
      val base = snapshots.lastOption.fold(Loaded(0L, evolve.initial)) { (_, file) =>
        val snap = parser.decode[Snapshot[S]](Files.readString(file, UTF_8)).fold(e => throw unreadable(file, e.getMessage), identity)
        Loaded(snap.seq, snap.state)
      }
      val files = segments
      files.zipWithIndex.foldLeft(base) { case (acc, ((_, file), i)) =>
        val lines    = Files.readAllLines(file, UTF_8).asScala.toVector.filter(_.nonEmpty)
        val lastFile = i == files.size - 1
        lines.zipWithIndex.foldLeft(acc) { case (state, (line, j)) =>
          parser.decode[Entry[E]](line) match
            case Right(entry) if entry.seq <= state.seq     => state
            case Right(entry) if entry.seq == state.seq + 1 => Loaded(entry.seq, evolve(state.state, entry.event))
            case Right(entry) => throw unreadable(file, s"event ${entry.seq} follows ${state.seq}")
            case Left(_) if lastFile && j == lines.size - 1 => state // cut short by a crash: never answered
            case Left(e) => throw unreadable(file, s"line ${j + 1}: ${e.getMessage}")
        }
      }

    private def unreadable(file: Path, why: String) = IllegalStateException(s"journal $file is unreadable: $why")

    private def create(file: Path, options: StandardOpenOption*): FileChannel =
      val opts = options.toSet.asJava
      if posix then FileChannel.open(file, opts, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      else FileChannel.open(file, opts)

    /** A fresh segment for events from `seq` on. A file of that name holds nothing valid (its first event would
      * have been `seq`, which was not read), so it is overwritten.
      */
    def startSegment(seq: Long): Unit =
      segment.foreach(_.close())
      val file = dir.resolve(name("events", seq, ".jsonl"))
      segment = Some(create(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
      syncDir()

    def write(firstSeq: Long, events: List[E]): Unit =
      val text = events.zipWithIndex.map((e, i) => printer.print(Entry(firstSeq + i, e).asJson) + "\n").mkString
      val buf  = ByteBuffer.wrap(text.getBytes(UTF_8))
      val chan = segment.getOrElse(throw IllegalStateException("journal is closed"))
      while buf.hasRemaining do chan.write(buf): Unit
      if fsync then chan.force(false)

    /** The state after event `seq`, written aside and renamed into place; the files it covers then go. */
    def snapshot(seq: Long, state: S): Unit =
      val file = dir.resolve(name("snapshot", seq, ".json"))
      val tmp  = dir.resolve(name("snapshot", seq, ".json.tmp"))
      val chan = create(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
      try
        val buf = ByteBuffer.wrap(printer.print(Snapshot(seq, state).asJson).getBytes(UTF_8))
        while buf.hasRemaining do chan.write(buf): Unit
        chan.force(true)
      finally chan.close()
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      syncDir()
      startSegment(seq + 1)
      snapshots.filter(_._1 < seq).foreach((_, p) => Files.deleteIfExists(p))
      segments.filter(_._1 <= seq).foreach((_, p) => Files.deleteIfExists(p))

    /** A rename or a new file is durable once the directory is synced (Linux; elsewhere a no-op if it cannot). */
    private def syncDir(): Unit =
      if fsync then
        try
          val d = FileChannel.open(dir, StandardOpenOption.READ)
          try d.force(true)
          finally d.close()
        catch case _: java.io.IOException => ()

    def close(): Unit =
      segment.foreach(_.close())
      segment = None
