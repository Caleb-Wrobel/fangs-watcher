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

  private def buildWatcher(config: Config): Watcher = ???

  /** Tick every `period`, forever.
    *
    * `onTick` swallows its own failures; a task that throws is silently dropped
    * from the schedule, which would strand the dead-man's switch answering
    * `/healthz` while never paging again.
    */
  private def startTicker(config: Config, watcher: Watcher): ScheduledExecutorService = ???
