package watcher

import java.time.Instant

/** The rules, in isolation. SPEC.md § The rules.
  *
  * These drive the clock and the webhook by hand, so they can assert the paths
  * the end-to-end smoke test cannot force: a notification that fails to send,
  * and a ping that lands while a page is mid-flight.
  */
class WatcherTest extends munit.FunSuite:

  val Period = 10
  val Grace = 20
  val Deadline: Int = Period + Grace

  val tmp: FunFixture[os.Path] = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "watcher-rules-test"),
    teardown = os.remove.all(_)
  )

  val T0: Instant = Instant.parse("2026-07-19T12:00:00Z")

  /** A watcher over a temp statefile, with time and the webhook under our thumb.
    *
    * `now` is a `var` the test moves by hand — the clock the watcher reads is
    * `() => now`, so advancing it is a plain assignment, no sleeping.
    */
  def make(
      dir: os.Path,
      initial: State = State(),
      now: () => Instant = () => T0
  ): (Watcher, FakeNotifier) =
    val config = Config(
      token = "t",
      discordWebhook = "http://localhost:1/hook",
      subject = "limen",
      periodSeconds = Period,
      graceSeconds = Grace,
      stateFile = dir / "state.json"
    )
    val notifier = new FakeNotifier
    (new Watcher(config, notifier, initial, now), notifier)

  // ── Rule 1: a ping refreshes lastSeen and clears an outstanding alert ──

  tmp.test("an accepted ping sets lastSeen") { dir =>
    val (watcher, _) = make(dir, now = () => T0)
    watcher.recordPing()
    assertEquals(watcher.state.lastSeen, Some(T0))
  }

  tmp.test("a ping while disarmed announces nothing") { dir =>
    val (watcher, notifier) = make(dir)
    // No alert outstanding, so nothing to recover: recordPing returns None.
    assertEquals(watcher.recordPing(), None)
    assertEquals(notifier.sent, Nil)
  }

  tmp.test("a ping while alerted clears the alert and announces a recovery") { dir =>
    val (watcher, notifier) = make(dir, State(Some(T0), alerted = true), now = () => T0)
    watcher.recordPing() match
      case Some(at) => watcher.announceRecovery(at)
      case None     => fail("a ping while alerted should return a timestamp to recover for")
    assertEquals(watcher.state.alerted, false)
    assertEquals(notifier.recoveries.size, 1)
    assert(clue(notifier.recoveries.head).startsWith("✅ limen is back"))
  }

  // ── Rule 2: one page per outage, after period + grace ──

  tmp.test("a tick within period + grace does not page") { dir =>
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    now = T0.plusSeconds(Deadline.toLong) // exactly at the deadline, not past it
    watcher.onTick()
    assertEquals(notifier.pages, Nil)
    assertEquals(watcher.state.alerted, false)
  }

  tmp.test("a tick past period + grace pages once") { dir =>
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick()
    assertEquals(notifier.pages.size, 1)
    assertEquals(watcher.state.alerted, true)
    assert(clue(notifier.pages.head).startsWith("🚨 limen is dark"))
  }

  tmp.test("further ticks during the same outage do not page again") { dir =>
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick()
    now = T0.plusSeconds(Deadline.toLong + Period + 1)
    watcher.onTick()
    now = T0.plusSeconds(Deadline.toLong + 2 * Period + 1)
    watcher.onTick()
    assertEquals(notifier.pages.size, 1, "alerted must suppress the repeat page")
  }

  // ── Rule 3: armed by the first ping only ──

  tmp.test("a watcher that has never been pinged never pages, however long it ticks") { dir =>
    var now = T0
    val (watcher, notifier) = make(dir, State(), now = () => now) // lastSeen absent
    for i <- 1 to 100 do
      now = T0.plusSeconds(i.toLong * Period)
      watcher.onTick()
    assertEquals(notifier.pages, Nil)
    assertEquals(watcher.state.lastSeen, None)
  }

  // ── Rule 4: a restart during an outage still pages ──

  tmp.test("state loaded mid-outage pages on the first tick after restart") { dir =>
    // The restart: a fresh watcher over state that is already dark but not yet
    // alerted — exactly what load() would return after a crash mid-outage.
    val loaded = State(Some(T0), alerted = false)
    val (watcher, notifier) = make(dir, loaded, now = () => T0.plusSeconds(Deadline.toLong + 1))
    watcher.onTick()
    assertEquals(notifier.pages.size, 1)
    assertEquals(watcher.state.alerted, true)
  }

  tmp.test("state loaded with alerted already true stays silent — no duplicate page") { dir =>
    val loaded = State(Some(T0), alerted = true)
    val (watcher, notifier) = make(dir, loaded, now = () => T0.plusSeconds(Deadline.toLong + 1))
    watcher.onTick()
    assertEquals(notifier.pages, Nil, "an already-alerted restart must not re-page")
  }

  // ── Rule 5: alerted means delivered, not attempted ──

  tmp.test("a down page that fails to send leaves alerted false") { dir =>
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    notifier.working = false // every send fails
    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick()
    // alerted=true would silence the retry and lose the outage entirely.
    assertEquals(watcher.state.alerted, false)
    assertEquals(State.load(dir / "state.json").alerted, false, "the false must be persisted too")
  }

  tmp.test("a failed down page is retried on the next tick, with a fresh elapsed") { dir =>
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    notifier.working = false
    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick() // fails
    notifier.working = true
    now = T0.plusSeconds(Deadline.toLong + Period + 1)
    watcher.onTick() // lands
    assertEquals(notifier.pages.size, 1, "the retry should deliver exactly one page")
    assertEquals(watcher.state.alerted, true)
  }

  tmp.test("a failed recovery leaves alerted true, and the next ping retries it") { dir =>
    val (watcher, notifier) = make(dir, State(Some(T0), alerted = true), now = () => T0)
    notifier.working = false
    watcher.recordPing() match
      case Some(at) => watcher.announceRecovery(at) // fails to send
      case None     => fail("expected a recovery timestamp")
    assertEquals(watcher.state.alerted, true, "a failed recovery re-arms so a later ping retries")

    notifier.working = true
    watcher.recordPing() match
      case Some(at) => watcher.announceRecovery(at) // lands
      case None     => fail("the outstanding alert should still recover")
    assertEquals(watcher.state.alerted, false)
    assertEquals(notifier.recoveries.size, 1)
  }

  tmp.test("a ping arriving mid-send wins: the watcher ends disarmed and does not re-arm") { dir =>
    // The subject is demonstrably alive, so the page no longer applies. Uses
    // FakeNotifier.duringSend to land the ping while the down page is in flight.
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    notifier.working = false // the down send will fail...
    notifier.duringSend = () =>
      now = T0.plusSeconds(Deadline.toLong + 5) // ...but a ping lands first
      watcher.recordPing()

    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick()

    // The rollback must see the ping's lastSeen, decline to re-arm, and leave
    // the watcher disarmed — not paging a subject that just checked in.
    assertEquals(watcher.state.alerted, false)
  }

  tmp.test("a failed send must not retract an alert a later outage legitimately raised") { dir =>
    // The subtle half of rule 5's guard, and the one the simpler mid-send tests
    // do not reach: they roll back on `alerted` alone, because a ping cleared
    // it. Here a *second* outage re-raises the alert before the first tick's
    // send fails — so `alerted` is true again, and only the lastSeen check tells
    // the stale rollback to keep its hands off a page that is genuinely owed.
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    val secondOutageSeen = T0.plusSeconds(Deadline.toLong + 5)

    notifier.working = false // the first tick's down send will fail
    var reentered = false
    notifier.duringSend = () =>
      if !reentered then
        reentered = true // fire once — the nested tick would recurse otherwise
        // A ping ends the first outage, then a second outage forms and a working
        // tick pages for it — all while the first send is still in flight.
        now = secondOutageSeen
        watcher.recordPing()
        now = secondOutageSeen.plusSeconds(Deadline.toLong + 1)
        notifier.working = true
        watcher.onTick() // second down page, delivered
        notifier.working = false // so the outer (first) send still returns false

    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick() // first tick: marks, sends, fails after the reentry above

    // The second outage's page is real and outstanding; the first tick's failed
    // rollback must not clear it.
    assertEquals(watcher.state.alerted, true, "the stale rollback retracted a live alert")
    assertEquals(notifier.pages.size, 1, "exactly the second outage's page was delivered")
    assertEquals(watcher.state.lastSeen, Some(secondOutageSeen))
  }

  tmp.test("a ping arriving mid-send keeps its own lastSeen, not the pre-send one") { dir =>
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    val pingAt = T0.plusSeconds(Deadline.toLong + 5)
    notifier.working = false
    notifier.duringSend = () =>
      now = pingAt
      watcher.recordPing()

    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick()
    assertEquals(watcher.state.lastSeen, Some(pingAt), "the mid-send ping's check-in must survive")
  }

  // ── Robustness ──

  tmp.test("a tick whose webhook throws does not propagate — the schedule survives") { dir =>
    // A ScheduledExecutorService silently stops rescheduling a task that threw,
    // which would strand the watcher serving /healthz while never paging again.
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    notifier.duringSend = () => throw new RuntimeException("webhook blew up")
    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick() // must not throw

    // A thrown send is not a clean `false`: it may have delivered, so the alert
    // stays marked rather than rolling back into a possible double-page. What
    // matters is that the watcher is not wedged — clear the fault and a ping
    // still recovers it.
    notifier.duringSend = () => ()
    watcher.recordPing() match
      case Some(at) => watcher.announceRecovery(at)
      case None     => fail("the marked alert should still be recoverable by a ping")
    assertEquals(watcher.state.alerted, false)
    assertEquals(notifier.recoveries.size, 1)
  }

  tmp.test("the heartbeat fires on every tick, before the rule is evaluated") { dir =>
    var now = T0
    val (watcher, notifier) = make(dir, State(Some(T0)), now = () => now)
    watcher.onTick()
    watcher.onTick()
    assertEquals(notifier.heartbeats, 2)
  }

  tmp.test("every state change is persisted, so a restart sees it") { dir =>
    val path = dir / "state.json"
    var now = T0
    val (watcher, _) = make(dir, State(Some(T0)), now = () => now)
    watcher.recordPing()
    assertEquals(State.load(path).lastSeen, Some(T0), "the ping must reach the statefile")

    now = T0.plusSeconds(Deadline.toLong + 1)
    watcher.onTick()
    assertEquals(State.load(path).alerted, true, "the page must reach the statefile")
  }
