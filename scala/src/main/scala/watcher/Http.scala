package watcher

/** The HTTP surface: exactly `POST /ping/{token}` and `GET /healthz`. */
final class WatcherRoutes(config: Config, watcher: Watcher) extends cask.MainRoutes:

  override def host: String = config.bind
  override def port: Int = config.port

  /** Record a check-in. A wrong token is a 404: the endpoint does not admit it
    * exists (SPEC.md § Security).
    *
    * The comparison must be constant-time — the token is a bearer credential,
    * and a timing oracle would let it be guessed a character at a time. Use
    * `java.security.MessageDigest.isEqual`, not `==`.
    *
    * The check-in must be durable before the 200 is written, but the recovery
    * notice must not delay it; hand the notice to another thread.
    */
  @cask.post("/ping/:token")
  def ping(token: String): cask.Response[String] = ???

  /** Our own liveness, for the pod's probe — not the subject's. */
  @cask.get("/healthz")
  def healthz(): cask.Response[String] = ???

  /** Answer a method mismatch with 404 rather than Cask's default 405.
    *
    * A 405 on `GET /ping/anything` would confirm the route exists whatever token
    * was tried, which is precisely what SPEC.md § Security rules out. An unknown
    * path and a known path with the wrong method must be indistinguishable.
    */
  override def handleNotFound(request: cask.Request): cask.Response.Raw = notFound()
  override def handleMethodNotAllowed(request: cask.Request): cask.Response.Raw = notFound()

  private def notFound(): cask.Response.Raw = ???

  initialize()
