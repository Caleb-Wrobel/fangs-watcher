package watcher

/** SPEC.md § Configuration — every variable, its default, and its refusal.
  *
  * Config is validated at boot precisely so a bad value is a refusal to start
  * rather than an exception from the scheduler thread at 3am; these assert the
  * refusals actually happen.
  */
class ConfigTest extends munit.FunSuite:

  val required: Map[String, String] = Map(
    "WATCHER_TOKEN" -> "t",
    "WATCHER_DISCORD_WEBHOOK" -> "http://localhost:9099/hook"
  )

  test("the two required variables are enough to boot".ignore) { ??? }

  test("a missing token is refused".ignore) { ??? }

  test("a missing webhook is refused".ignore) { ??? }

  test("every problem is reported at once, not just the first".ignore) { ??? }

  test("no error message contains a secret's value".ignore) { ??? }

  test("the defaults match the SPEC table".ignore) {
    // subject="the subject", period=300, grace=900, state=./watcher-state.json,
    // bind=127.0.0.1, port=8080, healthcheck unset.
    ???
  }

  test("bind defaults to loopback — the default must fail closed".ignore) { ??? }

  test("a non-numeric period is refused".ignore) { ??? }

  test("a zero or negative period is refused".ignore) { ??? }

  test("a port outside 1-65535 is refused".ignore) { ??? }

  test("deadline is period + grace".ignore) { ??? }

  test("an unset healthcheck url is None, not an empty string".ignore) { ??? }
