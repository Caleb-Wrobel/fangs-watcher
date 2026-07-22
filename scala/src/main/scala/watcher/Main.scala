package watcher

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

/** Entrypoint: config, logging, the scheduler, then the server.
  *
  * The ticker is a plain `ScheduledExecutorService` rather than anything from a
  * concurrency library — SPEC's rule 2 is "do this every period", and the JDK
  * already says that. `scheduleAtFixedRate` is deliberate: the contract counts
  * elapsed wall-clock since `lastSeen`, so a tick that runs late is still
  * correct, and fixed-rate keeps the cadence from drifting over days of uptime.
  */
object Main:

  def main(args: Array[String]): Unit =
    Config.fromEnv() match
      case Left(problems) =>
        // Fail loudly at boot rather than midway through an incident. The errors
        // name the offending variables but never their values, which may be
        // secrets.
        System.err.println("invalid configuration (see SPEC.md § Configuration):")
        problems.foreach(p => System.err.println(s"  $p"))
        sys.exit(2)

      case Right(config) =>
        val watcher = buildWatcher(config)
        val scheduler = startTicker(config, watcher)
        sys.addShutdownHook(scheduler.shutdownNow().nn: Unit)
        // Blocks; Cask owns the main thread from here.
        new WatcherRoutes(config, watcher).main(Array.empty)

  private def buildWatcher(config: Config): Watcher =
    val notifier = new HttpNotifier(config.discordWebhook, config.healthcheckUrl)
    // Rule 4: load whatever survived the last run, so a restart mid-outage still
    // pages on its first tick. An absent or corrupt file loads as fresh.
    val state = State.load(config.stateFile)
    System.err.println(
      s"watching ${config.subject} — listening on ${config.bind}:${config.port}, " +
        s"state at ${config.stateFile} (dark after ${config.deadlineSeconds}s of silence)"
    )
    new Watcher(config, notifier, state)

  /** Tick every `period`, forever.
    *
    * `onTick` swallows its own failures; a task that throws is silently dropped
    * from the schedule, which would strand the dead-man's switch answering
    * `/healthz` while never paging again.
    */
  private def startTicker(config: Config, watcher: Watcher): ScheduledExecutorService =
    val scheduler = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "watcher-ticker")
      t.setDaemon(true) // the JVM must not linger on the ticker at shutdown
      t
    }
    // Fixed *delay*, not fixed *rate*: the next tick is scheduled `period` after
    // the previous one finishes, so a slow webhook that blocks a tick does not
    // leave `scheduleAtFixedRate` to fire a burst of backlogged ticks the instant
    // it returns — each of which could send. This matches the Python impl's
    // `sleep(period)` loop. The mild cadence stretch during a slow send is
    // harmless: the rule counts wall-clock since `lastSeen`, so a late tick is
    // still correct.
    scheduler.scheduleWithFixedDelay(
      // A belt-and-suspenders guard: onTick already swallows, but a throw that
      // escaped anyway would silently unschedule the task for good.
      () => try watcher.onTick()
      catch case e: Throwable => System.err.println(s"tick failed; continuing: $e"),
      config.periodSeconds.toLong,
      config.periodSeconds.toLong,
      TimeUnit.SECONDS
    )
    scheduler
