package watcher

/** Configuration, per SPEC.md § Configuration — environment only, 12-factor.
  *
  * Every setting is declared once, with its type and default, and validated at
  * startup. A bad `WATCHER_PERIOD_SECONDS` should be a refusal to boot, not a
  * `NumberFormatException` from the scheduler thread at 3am.
  */
final case class Config(
    token: String,
    discordWebhook: String,
    subject: String = "the subject",
    periodSeconds: Int = 300,
    graceSeconds: Int = 900,
    stateFile: os.Path = os.pwd / "watcher-state.json",
    // Loopback by default: in production this runs as one container in a
    // `podman kube play` pod, and the networking sidecar sharing the namespace
    // reaches it over 127.0.0.1. Binding all interfaces would publish /ping on
    // the pod IP, bypassing the intended front door. See SPEC.md § Security.
    bind: String = "127.0.0.1",
    port: Int = 8080,
    healthcheckUrl: Option[String] = None
):

  /** How long a silence may run before it counts as dark (SPEC rule 2). */
  def deadlineSeconds: Int = periodSeconds + graceSeconds

object Config:

  /** Read the `WATCHER_*` environment.
    *
    * Returns every problem at once rather than the first, so a misconfigured
    * deploy is fixed in one pass. Messages name the offending variable but
    * never its value — two of them are secrets (SPEC.md § Configuration).
    */
  def fromEnv(env: Map[String, String] = sys.env): Either[List[String], Config] = ???
