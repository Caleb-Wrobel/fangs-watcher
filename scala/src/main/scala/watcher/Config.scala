package watcher

import scala.util.Try

/** Configuration, per SPEC.md § Configuration — environment only, 12-factor.
  *
  * Every setting is declared once, with its type and default, and validated at
  * startup. A bad `WATCHER_PERIOD_SECONDS` should be a refusal to boot, not a
  * `NumberFormatException` from the scheduler thread at 3am.
  */
final case class Config(
    token: String,
    discordWebhook: String,
    subject: String = Config.DefaultSubject,
    periodSeconds: Int = Config.DefaultPeriodSeconds,
    graceSeconds: Int = Config.DefaultGraceSeconds,
    stateFile: os.Path = Config.DefaultStateFile,
    // Loopback by default: in production this runs as one container in a
    // `podman kube play` pod, and the networking sidecar sharing the namespace
    // reaches it over 127.0.0.1. Binding all interfaces would publish /ping on
    // the pod IP, bypassing the intended front door. See SPEC.md § Security.
    bind: String = Config.DefaultBind,
    port: Int = Config.DefaultPort,
    healthcheckUrl: Option[String] = None
):

  /** How long a silence may run before it counts as dark (SPEC rule 2). */
  def deadlineSeconds: Int = periodSeconds + graceSeconds

object Config:

  // The SPEC.md § Configuration table, in code exactly once: the case class
  // above reads its parameter defaults from here, and so does `fromEnv`.
  val DefaultSubject: String = "the subject"
  val DefaultPeriodSeconds: Int = 300
  val DefaultGraceSeconds: Int = 900
  def DefaultStateFile: os.Path = os.pwd / "watcher-state.json"
  val DefaultBind: String = "127.0.0.1"
  val DefaultPort: Int = 8080

  /** Read the `WATCHER_*` environment.
    *
    * Returns every problem at once rather than the first, so a misconfigured
    * deploy is fixed in one pass. Messages name the offending variable but
    * never its value — two of them are secrets (SPEC.md § Configuration).
    */
  def fromEnv(env: Map[String, String] = sys.env): Either[List[String], Config] =
    val problems = List.newBuilder[String]

    /** A variable that is set to blank is a variable that is not set. */
    def raw(name: String): Option[String] =
      env.get(name).map(_.trim).filter(_.nonEmpty)

    def required(name: String): String =
      raw(name).getOrElse {
        problems += s"$name is required"
        ""
      }

    /** Parse an integer, falling back to `default` so that a later variable is
      * still checked — the point is to report every problem in one pass.
      */
    def intVar(name: String, default: Int, valid: Int => Boolean, expectation: String): Int =
      raw(name) match
        case None => default
        case Some(text) =>
          text.toIntOption.filter(valid).getOrElse {
            problems += s"$name $expectation"
            default
          }

    def pathVar(name: String, default: os.Path): os.Path =
      raw(name) match
        case None => default
        case Some(text) =>
          // A relative path resolves against the working directory; bare
          // `os.Path(text)` would throw on anything but an absolute path.
          Try(os.Path(text, os.pwd)).getOrElse {
            problems += s"$name is not a usable path"
            default
          }

    val config = Config(
      token = required("WATCHER_TOKEN"),
      discordWebhook = required("WATCHER_DISCORD_WEBHOOK"),
      subject = raw("WATCHER_SUBJECT").getOrElse(DefaultSubject),
      periodSeconds = intVar(
        "WATCHER_PERIOD_SECONDS",
        DefaultPeriodSeconds,
        _ > 0,
        "must be a positive integer"
      ),
      graceSeconds = intVar(
        "WATCHER_GRACE_SECONDS",
        DefaultGraceSeconds,
        _ >= 0,
        "must be a non-negative integer"
      ),
      stateFile = pathVar("WATCHER_STATE_FILE", DefaultStateFile),
      bind = raw("WATCHER_BIND").getOrElse(DefaultBind),
      port = intVar(
        "WATCHER_PORT",
        DefaultPort,
        p => p >= 1 && p <= 65535,
        "must be an integer between 1 and 65535"
      ),
      healthcheckUrl = raw("WATCHER_HEALTHCHECK_URL")
    )

    problems.result() match
      case Nil      => Right(config)
      case reported => Left(reported)
