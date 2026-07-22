package watcher

import java.time.Instant

/** SPEC.md § Notifications — the exact wording, and the `{elapsed}` format.
  *
  * The smoke test deliberately does not pin `{elapsed}`, so this is the only
  * place it is checked. SPEC only requires "human-readable (e.g. `18m`)"; these
  * pin the choice this implementation made so it cannot drift silently.
  */
class MessagesTest extends munit.FunSuite:

  val lastSeen: Instant = Instant.parse("2026-07-19T12:00:00Z")

  /** Assert a whole table at once, naming the input that failed. */
  def checkDurations(cases: (Long, String)*): Unit =
    cases.foreach((seconds, expected) =>
      assertEquals(Messages.humanDuration(seconds), expected, clue(seconds))
    )

  test("a sub-minute duration reads in seconds: 45s") {
    checkDurations(0L -> "0s", 1L -> "1s", 45L -> "45s", 59L -> "59s")
  }

  test("a sub-hour duration reads in whole minutes: 18m") {
    // Truncated, never rounded: the reader wants a magnitude, not a
    // measurement, and 89s rounding up to 2m would overstate the outage.
    checkDurations(60L -> "1m", 89L -> "1m", 1080L -> "18m", 3599L -> "59m")
  }

  test("an hours-long duration reads as 2h 05m, with the minutes zero-padded") {
    checkDurations(3600L -> "1h 00m", 7500L -> "2h 05m", 45296L -> "12h 34m")
  }

  test("a long outage keeps counting in hours — there is no day unit") {
    // Deliberate, and tested because it reads like an oversight: `26h` is
    // quicker to reason about at 3am than `1d 2h`.
    checkDurations(86400L -> "24h 00m", 93600L -> "26h 00m")
  }

  test("a negative or zero duration reads as 0s rather than going backwards") {
    // A clock that steps backwards mid-outage must not page about `-3m`.
    checkDurations(0L -> "0s", -1L -> "0s", -3600L -> "0s")
  }

  test("the down message matches SPEC's wording, with elapsed and last_seen") {
    // The whole string, not a prefix: smoke asserts the prefix, so everything
    // after it is checked here or nowhere.
    assertEquals(
      Messages.down("limen", 1080, lastSeen),
      "🚨 limen is dark — no check-in for 18m. Last check-in: 2026-07-19T12:00:00Z."
    )
  }

  test("the recovery message matches SPEC's wording, with the resume time") {
    assertEquals(
      Messages.recovery("limen", Instant.parse("2026-07-19T12:30:00Z")),
      "✅ limen is back — check-in resumed at 2026-07-19T12:30:00Z."
    )
  }

  test("the subject is interpolated, not hard-coded") {
    assert(Messages.down("gateway-02", 60, lastSeen).startsWith("🚨 gateway-02 is dark"))
    assert(Messages.recovery("gateway-02", lastSeen).startsWith("✅ gateway-02 is back"))
  }

  test("timestamps render as iso8601") {
    // Pins Instant.toString over any DateTimeFormatter, and matches what
    // State.toJson writes — the statefile and the pages read alike.
    assert(clue(Messages.recovery("limen", lastSeen)).contains("2026-07-19T12:00:00Z"))
    assert(clue(Messages.down("limen", 60, lastSeen)).contains("2026-07-19T12:00:00Z"))
  }
