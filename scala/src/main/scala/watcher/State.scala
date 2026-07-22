package watcher

import java.time.Instant

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
  def toJson(state: State): String = ???

  /** Read state, treating an absent or unreadable file as fresh.
    *
    * A corrupt statefile is deliberately not fatal: refusing to boot would take
    * the watcher down for good over a file it can simply rewrite. It re-arms on
    * the next ping, which is the safe direction to fail — a watcher that is
    * briefly disarmed is better than one that is not running.
    */
  def load(path: os.Path): State = ???

  /** Write atomically: temp file in the same dir → fsync → rename.
    *
    * Same directory matters — a rename is only atomic within a filesystem. The
    * directory fsync is what actually makes the rename durable across a power
    * loss; without it the rename can still be lost.
    *
    * The smoke test checks this by inode: a rename gives the path a new one,
    * while an in-place rewrite keeps it (SPEC.md § The smoke test, check 7).
    */
  def save(state: State, path: os.Path): Unit = ???
