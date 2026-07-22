package watcher

import java.time.Instant
import scala.util.Try

/** SPEC.md § Statefile: the wire shape, the degrade-to-fresh rule, and the
  * atomic write.
  */
class StateTest extends munit.FunSuite:

  /** A fresh temp directory per test, removed afterwards. */
  val tmp: FunFixture[os.Path] = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "watcher-state-test"),
    teardown = os.remove.all(_)
  )

  val someTime: Instant = Instant.parse("2026-07-19T12:00:00Z")

  /** The path's inode — a rename gives it a new one, a rewrite keeps it. */
  def inodeOf(path: os.Path): Any =
    java.nio.file.Files.getAttribute(path.toNIO, "unix:ino")

  /** Whether file permissions actually bind this user; root ignores them, and
    * the two tests that inject an IO failure by revoking access are vacuous
    * there. Probed rather than assumed, since `user.name` lies in containers.
    */
  lazy val permissionsBite: Boolean =
    val probe = os.temp.dir(prefix = "watcher-perm-probe")
    try
      os.perms.set(probe, "r-xr-xr-x")
      Try(os.write(probe / "canary", "x")).isFailure
    finally
      os.perms.set(probe, "rwxr-xr-x")
      os.remove.all(probe)

  // ── The wire shape ──

  test("renders the SPEC's shape: last_seen as iso8601, alerted as a bool") {
    val json = ujson.read(State.toJson(State(Some(someTime), alerted = true)))
    assertEquals(json("last_seen").str, "2026-07-19T12:00:00Z")
    assertEquals(json("alerted").bool, true)
    // Two fields, and nothing else — the SPEC is explicit about that.
    assertEquals(json.obj.keys.toSet, Set("last_seen", "alerted"))
  }

  test("a never-pinged state renders last_seen as null, not as a missing key") {
    // A missing key and an explicit null read the same to `.get`, but only one
    // of them tells the person reading the file that the field exists at all.
    val rendered = State.toJson(State())
    assert(clue(rendered).contains("\"last_seen\""))
    assertEquals(ujson.read(rendered)("last_seen"), ujson.Null)
  }

  test("renders human-readably — you will cat this during an incident") {
    assert(clue(State.toJson(State(Some(someTime)))).contains("\n"), "should be indented")
  }

  tmp.test("round-trips through save and load") { dir =>
    val path = dir / "state.json"
    val original = State(Some(someTime), alerted = true)
    State.save(original, path)

    assertEquals(State.load(path), original)
  }

  tmp.test("reads another implementation's offset form, not only its own Z form") { dir =>
    // The statefile format is the contract's, not this impl's: Python writes
    // `+00:00` where this writes `Z`, and both are the same instant.
    val path = dir / "state.json"
    os.write(path, """{"last_seen": "2026-07-19T12:00:00+00:00", "alerted": false}""")

    assertEquals(State.load(path).lastSeen, Some(someTime))
  }

  // ── Degrade to fresh, never refuse to boot ──

  tmp.test("an absent statefile loads as fresh and disarmed") { dir =>
    // SPEC rule 3: no statefile means never pinged, which means never page.
    val state = State.load(dir / "nothing-here.json")
    assertEquals(state.lastSeen, None)
    assertEquals(state.alerted, false)
  }

  tmp.test("a corrupt statefile loads as fresh rather than throwing") { dir =>
    // Refusing to boot would take the dead-man's switch down permanently over a
    // file the watcher can simply rewrite on the next ping.
    val path = dir / "state.json"
    os.write(path, """{"last_seen": "2026-07-19T12:00:00+00:0""")

    assertEquals(State.load(path), State())
  }

  tmp.test("valid JSON that is not an object loads as fresh") { dir =>
    val path = dir / "state.json"
    os.write(path, "[1, 2, 3]")

    assertEquals(State.load(path), State())
  }

  tmp.test("an unparseable last_seen is treated as absent, keeping the rest") { dir =>
    // Field by field: one bad timestamp must not discard a good `alerted`, or a
    // restart mid-outage would forget it had already paged and page twice.
    val path = dir / "state.json"
    os.write(path, """{"last_seen": "yesterday-ish", "alerted": true}""")

    val state = State.load(path)
    assertEquals(state.lastSeen, None)
    assertEquals(state.alerted, true)
  }

  tmp.test("an unreadable statefile loads as fresh") { dir =>
    assume(permissionsBite, "this user ignores file permissions (root?)")
    val path = dir / "state.json"
    State.save(State(Some(someTime), alerted = true), path)
    os.perms.set(path, "---------")

    try assertEquals(State.load(path), State())
    finally os.perms.set(path, "rw-r--r--")
  }

  // ── The atomic write ──

  tmp.test("save writes the file when its directory does not yet exist") { dir =>
    val path = dir / "nested" / "deeper" / "state.json"
    State.save(State(Some(someTime)), path)

    assert(os.exists(path))
    assertEquals(State.load(path).lastSeen, Some(someTime))
  }

  tmp.test("the inode changes on every save — proof of a rename, not a rewrite") { dir =>
    // The only part of the atomic-write rule observable from outside the
    // process, and exactly what smoke check 7 asserts.
    val path = dir / "state.json"
    State.save(State(Some(someTime)), path)

    val inodes = (1 to 3).map { i =>
      State.save(State(Some(someTime.plusSeconds(i.toLong))), path)
      inodeOf(path)
    }
    assertEquals(clue(inodes).distinct.size, 3, "the file was rewritten in place, not renamed over")
  }

  tmp.test("no temp files are left beside the statefile after a save") { dir =>
    val path = dir / "state.json"
    (1 to 3).foreach(i => State.save(State(Some(someTime.plusSeconds(i.toLong))), path))

    assertEquals(os.list(dir).map(_.last), Seq("state.json"))
  }

  tmp.test("a failed save leaves the previous state intact, not a torn file") { dir =>
    assume(permissionsBite, "this user ignores file permissions (root?)")
    val path = dir / "state.json"
    val good = State(Some(someTime), alerted = false)
    State.save(good, path)

    // Revoke write on the directory, so the save fails partway. The observable
    // guarantee is not *where* it failed but that the previous complete state
    // survived and nothing was left beside it.
    os.perms.set(dir, "r-xr-xr-x")
    try
      intercept[Throwable](State.save(State(Some(someTime.plusSeconds(60)), alerted = true), path))
      assertEquals(State.load(path), good, "the previous state must survive")
    finally os.perms.set(dir, "rwxr-xr-x")

    assertEquals(os.list(dir).map(_.last), Seq("state.json"), "a temp file was left behind")
  }
