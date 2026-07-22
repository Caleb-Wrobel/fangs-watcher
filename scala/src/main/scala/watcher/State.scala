package watcher

import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.StandardOpenOption
import java.time.{Instant, OffsetDateTime}
import scala.util.Try

/** The two persisted fields, and the atomic write that protects them.
  *
  * SPEC.md § Statefile: JSON you can `cat` during an incident, written
  * atomically so that a crash mid-write leaves the previous complete state
  * rather than a torn file. Absent file means fresh and disarmed.
  *
  * `lastSeen` absent means never pinged — and so, disarmed (SPEC rule 3).
  */
final case class State(lastSeen: Option[Instant] = None, alerted: Boolean = false)

object State:

  /** Render as the SPEC's shape: `{"last_seen": <iso8601|null>, "alerted": <bool>}`.
    *
    * Hand-written rather than derived: the field names are snake_case in the
    * contract, and the wire format is the contract's, not Scala's.
    */
  def toJson(state: State): String =
    val fields = ujson.Obj(
      "last_seen" -> state.lastSeen.fold[ujson.Value](ujson.Null)(t => ujson.Str(t.toString)),
      "alerted" -> ujson.Bool(state.alerted)
    )
    // Indented because you will read this by eye, at 3am, over SSH.
    ujson.write(fields, indent = 2)

  /** Read state, treating an absent or unreadable file as fresh.
    *
    * A corrupt statefile is deliberately not fatal: refusing to boot would take
    * the watcher down for good over a file it can simply rewrite. It re-arms on
    * the next ping, which is the safe direction to fail — a watcher that is
    * briefly disarmed is better than one that is not running.
    */
  def load(path: os.Path): State =
    if !os.exists(path) then
      System.err.println(s"no statefile at $path — starting fresh and disarmed")
      State()
    else
      Try(ujson.read(os.read(path))).toOption.flatMap(_.objOpt) match
        case None =>
          System.err.println(s"unreadable statefile at $path — starting fresh and disarmed")
          State()
        case Some(fields) =>
          // Each field degrades on its own: a `last_seen` nobody can parse must
          // not also discard a perfectly good `alerted`.
          val lastSeen = fields.get("last_seen").flatMap(_.strOpt).flatMap { text =>
            val parsed = parseTimestamp(text)
            if parsed.isEmpty then
              System.err.println(s"unparseable last_seen in $path — treating as absent")
            parsed
          }
          val alerted = fields.get("alerted").flatMap(_.boolOpt).getOrElse(false)
          State(lastSeen, alerted)

  /** Accept any ISO-8601 instant, not only the `Z` form this impl writes.
    *
    * The statefile format is the contract's, so another implementation's file
    * must load here — the Python one writes the `+00:00` offset form.
    */
  private def parseTimestamp(text: String): Option[Instant] =
    Try(Instant.parse(text)).toOption
      .orElse(Try(OffsetDateTime.parse(text).toInstant).toOption)

  /** Write atomically: temp file in the same dir → fsync → rename.
    *
    * Same directory matters — a rename is only atomic within a filesystem. The
    * directory fsync is what actually makes the rename durable across a power
    * loss; without it the rename can still be lost.
    *
    * The smoke test checks this by inode: a rename gives the path a new one,
    * while an in-place rewrite keeps it (SPEC.md § The smoke test, check 7).
    */
  def save(state: State, path: os.Path): Unit =
    val dir = path / os.up
    os.makeDir.all(dir)
    // Dotted prefix so a temp glimpsed mid-write does not look like the real
    // thing to anyone watching the directory.
    val temp = os.temp(dir = dir, prefix = s".${path.last}.", suffix = ".tmp", deleteOnExit = false)
    try
      val out = new FileOutputStream(temp.toIO)
      try
        out.write(toJson(state).getBytes(UTF_8))
        out.flush()
        // The data must be on the platter before the rename publishes it;
        // otherwise a power loss can leave the new name over empty contents.
        out.getFD.sync()
      finally out.close()

      os.move(temp, path, replaceExisting = true, atomicMove = true)
      fsyncDir(dir)
    catch
      case e: Throwable =>
        // Never leave the temp beside the statefile: the SPEC promises the
        // directory is clean in normal operation, and a half-written sibling
        // is exactly the confusion an incident does not need.
        Try(os.remove(temp))
        throw e

  /** fsync a directory, so the rename itself survives a power loss.
    *
    * Not portable to Windows, where a directory cannot be opened; the watcher
    * targets Linux containers, and a missed fsync here is not worth failing an
    * otherwise complete write over.
    */
  private def fsyncDir(dir: os.Path): Unit =
    Try {
      val channel = FileChannel.open(dir.toNIO, StandardOpenOption.READ)
      try channel.force(true)
      finally channel.close()
    }
    ()
