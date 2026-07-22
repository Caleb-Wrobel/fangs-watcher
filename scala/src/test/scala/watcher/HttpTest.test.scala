package watcher

/** SPEC.md § HTTP surface: two routes, and nothing else discoverable.
  *
  * The smoke test covers the happy paths end to end; what it cannot easily do is
  * enumerate the ways a framework leaks the route's existence. That is what
  * these are for.
  */
class HttpTest extends munit.FunSuite:

  /** Boot the routes on an ephemeral port, run the body, shut down. */
  def withServer(config: Config, watcher: Watcher)(body: String => Unit): Unit = ???

  test("GET /healthz is 200 while the process is up".ignore) { ??? }

  test("POST /ping/{token} with the right token is 200 and records the check-in".ignore) { ??? }

  test("POST /ping/{token} with a wrong token is 404, never 401 or 403".ignore) { ??? }

  test("GET /ping/{token} is 404 — indistinguishable from an unknown path".ignore) {
    // A 405 here would confirm the route exists whatever token was tried,
    // defeating the 404-on-mismatch rule as thoroughly as a 401 would.
    ???
  }

  test("POST /healthz is 404, not 405".ignore) { ??? }

  test("an unknown path is 404, byte-identical to a wrong-token ping".ignore) { ??? }

  test("the token comparison is constant-time".ignore) {
    // Not a timing measurement — assert the code path uses MessageDigest.isEqual
    // rather than ==. A timing oracle would let the token be guessed a character
    // at a time.
    ???
  }

  test("the ping is durable before the 200 is written".ignore) { ??? }

  test("a slow webhook does not delay the 200 on a check-in".ignore) { ??? }

  test("no response body or log line echoes the token".ignore) { ??? }
