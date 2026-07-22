package watcher

import io.undertow.Undertow

import java.time.Instant

/** SPEC.md § HTTP surface: two routes, and nothing else discoverable.
  *
  * The smoke test covers the happy paths end to end; what it cannot easily do is
  * enumerate the ways a framework leaks the route's existence. That is what
  * these are for.
  */
class HttpTest extends munit.FunSuite:

  val token = "a-token-for-tests"

  /** A fresh temp directory per test, removed afterwards. */
  val tmp: FunFixture[os.Path] = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "watcher-http-test"),
    teardown = os.remove.all(_)
  )

  /** A period long enough that the ticker never fires during a test — these
    * exercise the routes, not the rules.
    */
  def testConfig(dir: os.Path): Config = Config(
    token = token,
    discordWebhook = "http://127.0.0.1:1/hook",
    subject = "limen",
    periodSeconds = 3600,
    graceSeconds = 3600,
    stateFile = dir / "state.json"
  )

  /** Boot the routes on an ephemeral port, run the body, shut down.
    *
    * Cask's own `main` binds `config.port` and hands back no handle to stop what
    * it started, so a suite built on it would collide on a fixed port and leak an
    * Undertow per test. `defaultHandler` is the seam: it is the entire routing
    * table as a plain `HttpHandler`, and an Undertow we build ourselves can bind
    * port 0 and be stopped again. `config.bind` and `config.port` are therefore
    * not exercised here — Main owns those, and the smoke test covers them.
    *
    * The body receives the base URL, e.g. `http://127.0.0.1:38217`.
    */
  def withServer(config: Config, watcher: Watcher)(body: String => Unit): Unit =
    val routes = new WatcherRoutes(config, watcher)
    val server = Undertow.builder
      .addHttpListener(0, "127.0.0.1")
      .setHandler(routes.defaultHandler)
      .build()
    server.start()
    try
      val bound = server.getListenerInfo
        .get(0)
        .getAddress
        .asInstanceOf[java.net.InetSocketAddress]
        .getPort
      body(s"http://127.0.0.1:$bound")
    finally server.stop()

  /** The common case: a server over a throwaway statefile and a fresh watcher. */
  def withWatcher(dir: os.Path)(body: (String, Watcher) => Unit): Unit =
    val config = testConfig(dir)
    val watcher = new Watcher(config, new FakeNotifier, State())
    withServer(config, watcher)(base => body(base, watcher))

  tmp.test("GET /healthz is 200 while the process is up") { dir =>
    withWatcher(dir) { (base, _) =>
      assertEquals(requests.get(s"$base/healthz", check = false).statusCode, 200)
    }
  }

  tmp.test("POST /ping/{token} with the right token is 200 and records the check-in") {
    dir =>
      withWatcher(dir) { (base, watcher) =>
        assertEquals(requests.post(s"$base/ping/$token", check = false).statusCode, 200)
        assert(clue(watcher.state.lastSeen).isDefined)
        assert(!watcher.state.alerted)
      }
  }

  tmp.test("POST /ping/{token} with a wrong token is 404, never 401 or 403") { dir =>
    withWatcher(dir) { (base, watcher) =>
      assertEquals(requests.post(s"$base/ping/not-the-token", check = false).statusCode, 404)
      // A rejected ping is not a check-in: the subject's pulse was not proven.
      assert(clue(watcher.state.lastSeen).isEmpty)
    }
  }

  tmp.test("GET /ping/{token} is 404 — indistinguishable from an unknown path") { dir =>
    // A 405 here would confirm the route exists whatever token was tried,
    // defeating the 404-on-mismatch rule as thoroughly as a 401 would.
    withWatcher(dir) { (base, _) =>
      assertEquals(requests.get(s"$base/ping/$token", check = false).statusCode, 404)
    }
  }

  tmp.test("POST /healthz is 404, not 405") { dir =>
    withWatcher(dir) { (base, _) =>
      assertEquals(requests.post(s"$base/healthz", check = false).statusCode, 404)
    }
  }

  tmp.test("an unknown path is 404, byte-identical to a wrong-token ping") { dir =>
    withWatcher(dir) { (base, _) =>
      val unknown = requests.get(s"$base/no-such-route", check = false)
      val wrongToken = requests.post(s"$base/ping/not-the-token", check = false)
      assertEquals(unknown.statusCode, wrongToken.statusCode)
      // Identical bodies too: a difference in wording is a difference an
      // attacker can measure.
      assertEquals(unknown.text(), wrongToken.text())

      // Near misses on the real path are 404 as well — no route is discoverable
      // by trimming, extending, or recasing it.
      val nearMisses = List(
        "/ping",
        "/ping/",
        s"/ping/$token/extra",
        s"/PING/$token",
        s"/ping/${token.toUpperCase}"
      )
      nearMisses.foreach { path =>
        assertEquals(requests.post(s"$base$path", check = false).statusCode, 404, clue(path))
      }
    }
  }

  test("the token comparison is constant-time") {
    // Not a timing measurement — assert the code path uses MessageDigest.isEqual
    // rather than ==. A timing oracle would let the token be guessed a character
    // at a time. Timing this over a loopback socket would measure the scheduler,
    // not the comparison, so the source is the honest thing to assert on.
    val source = os.pwd / "src" / "main" / "scala" / "watcher" / "Http.scala"
    assert(os.exists(source), s"expected to read $source; run the suite from scala/")
    assert(
      os.read(source).contains("MessageDigest.isEqual"),
      "the token comparison must be constant-time"
    )
  }

  tmp.test("the ping is durable before the 200 is written") { dir =>
    // The 200 is a promise that the check-in survives a crash. A statefile
    // written after the response is a promise the watcher cannot keep.
    withWatcher(dir) { (base, _) =>
      assertEquals(requests.post(s"$base/ping/$token", check = false).statusCode, 200)
      assert(clue(State.load(dir / "state.json").lastSeen).isDefined)
    }
  }

  tmp.test("a slow webhook does not delay the 200 on a check-in") { dir =>
    val config = testConfig(dir)
    val notifier = new FakeNotifier
    // Already alerted, so this ping owes a recovery notice — the send that must
    // not sit on the request's critical path.
    val watcher = new Watcher(config, notifier, State(Some(Instant.now()), alerted = true))
    notifier.duringSend = () => Thread.sleep(2000)

    withServer(config, watcher) { base =>
      val startedAt = System.nanoTime()
      assertEquals(requests.post(s"$base/ping/$token", check = false).statusCode, 200)
      val elapsedMs = (System.nanoTime() - startedAt) / 1000000
      assert(clue(elapsedMs) < 1000, "the 200 waited on the webhook")
    }
  }

  tmp.test("no response body or log line echoes the token") { dir =>
    // SPEC.md § Configuration: no secret ever appears in a log line, and the
    // token in the path is a bearer credential.
    val guess = "a-guess-at-the-token"
    val captured = new java.io.ByteArrayOutputStream
    val (realOut, realErr) = (System.out, System.err)

    withWatcher(dir) { (base, _) =>
      val (accepted, rejected) =
        try
          // Cask logs to System.err directly, so Console.withErr does not reach
          // it. Keep the window narrow: this swap is process-global.
          System.setOut(new java.io.PrintStream(captured))
          System.setErr(new java.io.PrintStream(captured))
          (
            requests.post(s"$base/ping/$token", check = false),
            requests.post(s"$base/ping/$guess", check = false)
          )
        finally
          System.setOut(realOut)
          System.setErr(realErr)

      assert(!accepted.text().contains(token), "the accepted response echoed the token")
      assert(!rejected.text().contains(guess), "the 404 echoed what was tried")
      assert(!captured.toString.contains(token), "the token reached a log line")
    }
  }
