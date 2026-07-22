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

  /** The config an environment yields, failing the test if it was refused. */
  def config(env: Map[String, String]): Config =
    Config.fromEnv(env).fold(ps => fail(s"expected a Config, got: ${ps.mkString("; ")}"), identity)

  /** The problems an environment yields, failing the test if it was accepted. */
  def problems(env: Map[String, String]): List[String] =
    Config.fromEnv(env).fold(identity, c => fail(s"expected a refusal, got: $c"))

  test("the two required variables are enough to boot") {
    val result = config(required)
    assertEquals(result.token, "t")
    assertEquals(result.discordWebhook, "http://localhost:9099/hook")
  }

  test("a missing token is refused") {
    val result = Config.fromEnv(required - "WATCHER_TOKEN")
    assert(clue(result).isLeft)
    assert(result.left.exists(_.exists(_.contains("WATCHER_TOKEN"))))
  }

  test("a missing webhook is refused") {
    val result = Config.fromEnv(required - "WATCHER_DISCORD_WEBHOOK")
    assert(clue(result).isLeft)
    assert(result.left.exists(_.exists(_.contains("WATCHER_DISCORD_WEBHOOK"))))
  }

  test("a blank required variable is as good as an unset one") {
    val reported = problems(required ++ Map("WATCHER_TOKEN" -> "   "))
    assert(clue(reported).exists(_.contains("WATCHER_TOKEN")))
  }

  test("every problem is reported at once, not just the first") {
    // A misconfigured deploy should be fixable in one pass, so validation runs
    // to the end rather than stopping at the first refusal.
    val reported = problems(
      Map("WATCHER_PERIOD_SECONDS" -> "nope", "WATCHER_PORT" -> "0")
    )
    val named = List(
      "WATCHER_TOKEN",
      "WATCHER_DISCORD_WEBHOOK",
      "WATCHER_PERIOD_SECONDS",
      "WATCHER_PORT"
    )
    named.foreach(name => assert(clue(reported).exists(_.contains(name)), s"$name unreported"))
  }

  test("no error message contains a secret's value") {
    // SPEC.md § Configuration: no secret ever appears in a log line, and every
    // one of these messages is printed to stderr at boot by Main.
    val secretToken = "s3cret-token-value"
    val secretWebhook = "https://discord.example/api/webhooks/1234/abcdef"
    val reported = problems(
      Map(
        "WATCHER_TOKEN" -> secretToken,
        "WATCHER_DISCORD_WEBHOOK" -> secretWebhook,
        "WATCHER_PERIOD_SECONDS" -> "nope",
        "WATCHER_GRACE_SECONDS" -> "-1",
        "WATCHER_PORT" -> "65536"
      )
    )
    assert(clue(reported).nonEmpty)
    reported.foreach { message =>
      assert(!message.contains(secretToken), s"token leaked: $message")
      assert(!message.contains(secretWebhook), s"webhook leaked: $message")
    }
  }

  test("the defaults match the SPEC table") {
    // Deliberately literals rather than Config.Default*, so this is an
    // independent check of the table rather than a restatement of the code.
    val result = config(required)
    assertEquals(result.subject, "the subject")
    assertEquals(result.periodSeconds, 300)
    assertEquals(result.graceSeconds, 900)
    assertEquals(result.stateFile, os.pwd / "watcher-state.json")
    assertEquals(result.port, 8080)
    assertEquals(result.healthcheckUrl, None)
  }

  test("bind defaults to loopback — the default must fail closed") {
    // Binding all interfaces would publish /ping on the pod IP, bypassing the
    // networking sidecar that owns ingress. See SPEC.md § Security.
    val result = config(required)
    assertEquals(result.bind, "127.0.0.1")
    assertNotEquals(result.bind, "0.0.0.0")
  }

  test("an explicit bind is honoured — 0.0.0.0 remains available") {
    assertEquals(config(required + ("WATCHER_BIND" -> "0.0.0.0")).bind, "0.0.0.0")
  }

  test("a non-numeric period is refused") {
    val reported = problems(required + ("WATCHER_PERIOD_SECONDS" -> "five minutes"))
    assert(clue(reported).exists(_.contains("WATCHER_PERIOD_SECONDS")))
  }

  test("a zero or negative period is refused") {
    // A period of zero would tick in a hot loop; a negative one is nonsense.
    List("0", "-1").foreach { value =>
      val reported = problems(required + ("WATCHER_PERIOD_SECONDS" -> value))
      assert(clue(reported).exists(_.contains("WATCHER_PERIOD_SECONDS")), s"accepted $value")
    }
    // A zero grace, by contrast, is legitimate: no forgiveness beyond a period.
    assertEquals(config(required + ("WATCHER_GRACE_SECONDS" -> "0")).graceSeconds, 0)
  }

  test("a port outside 1-65535 is refused") {
    List("0", "65536", "-1").foreach { value =>
      val reported = problems(required + ("WATCHER_PORT" -> value))
      assert(clue(reported).exists(_.contains("WATCHER_PORT")), s"accepted $value")
    }
    List("1", "65535").foreach { value =>
      assertEquals(config(required + ("WATCHER_PORT" -> value)).port, value.toInt)
    }
  }

  test("deadline is period + grace") {
    val result = config(
      required ++ Map("WATCHER_PERIOD_SECONDS" -> "10", "WATCHER_GRACE_SECONDS" -> "20")
    )
    assertEquals(result.deadlineSeconds, 30)
  }

  test("an unset healthcheck url is None, not an empty string") {
    // Some("") would have Main heartbeat at a URL that cannot resolve, on
    // every tick, forever.
    assertEquals(config(required).healthcheckUrl, None)
    assertEquals(config(required + ("WATCHER_HEALTHCHECK_URL" -> "")).healthcheckUrl, None)
    assertEquals(
      config(required + ("WATCHER_HEALTHCHECK_URL" -> "https://hc.example/ping/abc")).healthcheckUrl,
      Some("https://hc.example/ping/abc")
    )
  }

  test("a relative state file resolves against the working directory") {
    // os.Path refuses a relative string outright, so this is the one variable
    // where the obvious parse throws on the common input.
    assertEquals(
      config(required + ("WATCHER_STATE_FILE" -> "state/watcher.json")).stateFile,
      os.pwd / "state" / "watcher.json"
    )
    assertEquals(
      config(required + ("WATCHER_STATE_FILE" -> "/var/lib/watcher/state.json")).stateFile,
      os.root / "var" / "lib" / "watcher" / "state.json"
    )
  }
