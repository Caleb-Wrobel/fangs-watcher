package watcher

import java.time.Instant

/** The rules, in isolation. SPEC.md § The rules.
  *
  * These drive the clock and the webhook by hand, so they can assert the paths
  * the end-to-end smoke test cannot force: a notification that fails to send,
  * and a ping that lands while a page is mid-flight.
  *
  * Every test is `.ignore`d while the implementation is a stub. Drop the
  * `.ignore` as you fill each one in — an ignored suite reports what is left.
  */
class WatcherTest extends munit.FunSuite:

  val Period = 10
  val Grace = 20
  val Deadline: Int = Period + Grace

  /** A watcher over a temp statefile, with time and the webhook under our thumb. */
  def make(
      initial: State = State(),
      now: () => Instant = () => Instant.now()
  ): (Watcher, FakeNotifier) = ???

  // ── Rule 1: a ping refreshes lastSeen and clears an outstanding alert ──

  test("an accepted ping sets lastSeen".ignore) { ??? }

  test("a ping while disarmed announces nothing".ignore) { ??? }

  test("a ping while alerted clears the alert and announces a recovery".ignore) { ??? }

  // ── Rule 2: one page per outage, after period + grace ──

  test("a tick within period + grace does not page".ignore) { ??? }

  test("a tick past period + grace pages once".ignore) { ??? }

  test("further ticks during the same outage do not page again".ignore) { ??? }

  // ── Rule 3: armed by the first ping only ──

  test("a watcher that has never been pinged never pages, however long it ticks".ignore) { ??? }

  // ── Rule 4: a restart during an outage still pages ──

  test("state loaded mid-outage pages on the first tick after restart".ignore) { ??? }

  test("state loaded with alerted already true stays silent — no duplicate page".ignore) { ??? }

  // ── Rule 5: alerted means delivered, not attempted ──

  test("a down page that fails to send leaves alerted false".ignore) { ??? }

  test("a failed down page is retried on the next tick, with a fresh elapsed".ignore) { ??? }

  test("a failed recovery leaves alerted true, and the next ping retries it".ignore) { ??? }

  test("a ping arriving mid-send wins: the watcher ends disarmed and does not re-arm".ignore) {
    // The subject is demonstrably alive, so the page no longer applies. Uses
    // FakeNotifier.duringSend to land the ping while the send is in flight.
    ???
  }

  test("a ping arriving mid-send keeps its own lastSeen, not the pre-send one".ignore) { ??? }

  // ── Robustness ──

  test("a tick whose webhook throws does not propagate — the schedule survives".ignore) {
    // A ScheduledExecutorService silently stops rescheduling a task that threw,
    // which would strand the watcher serving /healthz while never paging again.
    ???
  }

  test("the heartbeat fires on every tick, before the rule is evaluated".ignore) { ??? }

  test("every state change is persisted, so a restart sees it".ignore) { ??? }
