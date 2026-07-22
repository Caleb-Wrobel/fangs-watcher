package watcher

/** SPEC.md § Notifications — the exact wording, and the `{elapsed}` format.
  *
  * The smoke test deliberately does not pin `{elapsed}`, so this is the only
  * place it is checked. SPEC only requires "human-readable (e.g. `18m`)"; these
  * pin the choice this implementation made so it cannot drift silently.
  */
class MessagesTest extends munit.FunSuite:

  test("a sub-minute duration reads in seconds: 45s".ignore) { ??? }

  test("a sub-hour duration reads in whole minutes: 18m".ignore) { ??? }

  test("an hours-long duration reads as 2h 05m, with the minutes zero-padded".ignore) { ??? }

  test("a negative or zero duration reads as 0s rather than going backwards".ignore) { ??? }

  test("the down message matches SPEC's wording, with elapsed and last_seen".ignore) { ??? }

  test("the recovery message matches SPEC's wording, with the resume time".ignore) { ??? }

  test("timestamps render as iso8601".ignore) { ??? }
