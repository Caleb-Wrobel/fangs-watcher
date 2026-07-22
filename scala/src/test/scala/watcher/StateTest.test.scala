package watcher

/** SPEC.md § Statefile: the wire shape, the degrade-to-fresh rule, and the
  * atomic write.
  */
class StateTest extends munit.FunSuite:

  /** A fresh temp directory per test, removed afterwards. */
  val tmp: FunFixture[os.Path] = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "watcher-state-test"),
    teardown = os.remove.all(_)
  )

  // ── The wire shape ──

  test("renders the SPEC's shape: last_seen as iso8601, alerted as a bool".ignore) { ??? }

  test("a never-pinged state renders last_seen as null, not as a missing key".ignore) { ??? }

  test("round-trips through save and load".ignore) { ??? }

  // ── Degrade to fresh, never refuse to boot ──

  tmp.test("an absent statefile loads as fresh and disarmed".ignore) { _ => ??? }

  tmp.test("a corrupt statefile loads as fresh rather than throwing".ignore) { _ =>
    // Refusing to boot would take the dead-man's switch down permanently over a
    // file the watcher can simply rewrite on the next ping.
    ???
  }

  tmp.test("an unparseable last_seen is treated as absent, keeping the rest".ignore) { _ => ??? }

  tmp.test("an unreadable statefile loads as fresh".ignore) { _ => ??? }

  // ── The atomic write ──

  tmp.test("save writes the file when its directory does not yet exist".ignore) { _ => ??? }

  tmp.test("the inode changes on every save — proof of a rename, not a rewrite".ignore) { _ =>
    // The only part of the atomic-write rule observable from outside the
    // process, and exactly what smoke check 7 asserts. Read it via
    // java.nio.file.Files.getAttribute(path, "unix:ino").
    ???
  }

  tmp.test("no temp files are left beside the statefile after a save".ignore) { _ => ??? }

  tmp.test("a failed save leaves the previous state intact, not a torn file".ignore) { _ => ??? }
