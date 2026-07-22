package watcher

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** The HTTP surface: exactly `POST /ping/{token}` and `GET /healthz`. */
final class WatcherRoutes(config: Config, watcher: Watcher) extends cask.MainRoutes:

  override def host: String = config.bind
  override def port: Int = config.port

  // Cask's debug error page renders an endpoint's argument values into the
  // response body — which for `ping` is the token. Off, so a credential can
  // never ride out on a 500.
  override def debugMode: Boolean = false

  private val expectedToken = config.token.getBytes(UTF_8)

  /** Record a check-in. A wrong token is a 404: the endpoint does not admit it
    * exists (SPEC.md § Security).
    *
    * The comparison is constant-time — the token is a bearer credential, and a
    * timing oracle would let it be guessed a character at a time.
    *
    * The check-in is durable before the 200 is written (`recordPing` persists),
    * but the recovery notice must not delay it, so it rides its own thread.
    */
  @cask.post("/ping/:token")
  def ping(token: String): cask.Response[String] =
    if !MessageDigest.isEqual(token.getBytes(UTF_8), expectedToken) then notFound()
    else
      watcher.recordPing().foreach { recoveredAt =>
        val thread = new Thread(() => watcher.announceRecovery(recoveredAt), "recovery-notice")
        thread.setDaemon(true)
        thread.start()
      }
      cask.Response("ok", statusCode = 200)

  /** Our own liveness, for the pod's probe — not the subject's. */
  @cask.get("/healthz")
  def healthz(): cask.Response[String] = cask.Response("ok", statusCode = 200)

  /** Answer a method mismatch with 404 rather than Cask's default 405.
    *
    * A 405 on `GET /ping/anything` would confirm the route exists whatever token
    * was tried, which is precisely what SPEC.md § Security rules out. An unknown
    * path and a known path with the wrong method must be indistinguishable.
    */
  override def handleNotFound(request: cask.Request): cask.Response.Raw = notFound()
  override def handleMethodNotAllowed(request: cask.Request): cask.Response.Raw = notFound()

  // Byte-identical to every other 404, so a wrong token and an unknown path are
  // indistinguishable — no wording an attacker could measure.
  private def notFound(): cask.Response[String] = cask.Response("not found", statusCode = 404)

  initialize()
