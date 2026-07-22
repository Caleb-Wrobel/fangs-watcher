package watcher

import java.time.Instant

/** The contract itself: SPEC.md § The rules, and nothing about HTTP.
  *
  * Kept free of Cask so the rules can be read — and tested — on their own. This
  * is the file to compare against `python/watcher/core.py`.
  *
  * Concurrency: Cask serves each request on an Undertow worker thread while the
  * scheduler ticks on its own, so `recordPing` and `onTick` genuinely race. The
  * monitor is held only across in-memory mutation and the statefile write, never
  * across a network call — a wedged webhook must not block a ping.
  *
  * `clock` is injected so the tests can drive time by hand rather than sleep
  * through a real period.
  */
final class Watcher(
    config: Config,
    notifier: Notifier,
    initial: State,
    clock: () => Instant = () => Instant.now()
):

  /** The current state, for tests and for the ping handler's response. */
  def state: State = ???

  /** SPEC rule 1: an accepted ping refreshes `lastSeen` and clears any
    * outstanding alert.
    *
    * Returns the timestamp to announce a recovery for, or `None` if no alert was
    * outstanding. The announcement is left to the caller so the ping can be
    * acknowledged without waiting on the webhook.
    */
  def recordPing(): Option[Instant] = ???

  /** Send the recovery notice, after the ping has already been answered.
    *
    * On failure the alert goes back to outstanding so the next ping retries it —
    * unless the state has moved on meanwhile (SPEC rule 5).
    */
  def announceRecovery(at: Instant): Unit = ???

  /** One scheduler tick: heartbeat, then SPEC rule 2.
    *
    * Runs every `period`. Must never throw — a tick that dies takes the whole
    * dead-man's switch with it, and a `ScheduledExecutorService` silently stops
    * rescheduling a task that threw.
    */
  def onTick(): Unit = ???

  /** SPEC rule 2, and the retry half of rule 5.
    *
    * Marks `alerted` *before* sending, so a slow webhook cannot let the next tick
    * page twice; rolls the mark back if the send does not land — but only if
    * nothing has happened since. A ping that arrived mid-send has already cleared
    * the alert, and re-arming here would page for an outage that is over.
    */
  private def evaluate(): Unit = ???
