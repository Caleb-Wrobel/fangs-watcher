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

  /** The one mutable cell in the implementation.
    *
    * `State` is an immutable case class, so a mutation is `current = current
    * .copy(...)` rather than a field write — which means a reader who has hold
    * of a `State` has a snapshot nobody can change underneath them.
    */
  private var current: State = initial

  /** The current state, for tests and for the ping handler's response. */
  def state: State = synchronized(current)

  /** SPEC rule 1: an accepted ping refreshes `lastSeen` and clears any
    * outstanding alert.
    *
    * Returns the timestamp to announce a recovery for, or `None` if no alert was
    * outstanding. The announcement is left to the caller so the ping can be
    * acknowledged without waiting on the webhook.
    */
  def recordPing(): Option[Instant] =
    synchronized:
      val now = clock()
      val wasAlerted = current.alerted
      // Both fields move together, under the monitor: a reader must never see
      // the new lastSeen paired with the old alerted, a state that never was.
      persist(current.copy(lastSeen = Some(now), alerted = false))
      // Announce only if a page was outstanding — and let the caller do it, off
      // the request path, so a wedged webhook cannot delay the ping's 200.
      Option.when(wasAlerted)(now)

  /** Send the recovery notice, after the ping has already been answered.
    *
    * On failure the alert goes back to outstanding so the next ping retries it —
    * unless the state has moved on meanwhile (SPEC rule 5).
    */
  def announceRecovery(at: Instant): Unit =
    // Outside the monitor: the send may block, and a ping arriving meanwhile
    // must not wait on it.
    if notifier.notify(Messages.recovery(config.subject, at)) then ()
    else
      synchronized:
        // Only re-arm if this is still the ping we were recovering for. A newer
        // ping owns the state now, and its own recovery (or none) applies.
        if current.lastSeen.contains(at) then
          persist(current.copy(alerted = true))
          System.err.println("recovery failed to send; will retry on the next ping")

  /** One scheduler tick: heartbeat, then SPEC rule 2.
    *
    * Runs every `period`. Must never throw — a tick that dies takes the whole
    * dead-man's switch with it, and a `ScheduledExecutorService` silently stops
    * rescheduling a task that threw.
    */
  def onTick(): Unit =
    try
      // The heartbeat fires every tick, before the rule — so the managed floor
      // hears from a watcher that is up but has nothing to page about.
      notifier.heartbeat()
      evaluate()
    catch
      case e: Throwable =>
        System.err.println(s"tick failed; continuing: $e")

  /** SPEC rule 2, and the retry half of rule 5.
    *
    * Marks `alerted` *before* sending, so a slow webhook cannot let the next tick
    * page twice; rolls the mark back if the send does not land — but only if
    * nothing has happened since. A ping that arrived mid-send has already cleared
    * the alert, and re-arming here would page for an outage that is over.
    */
  private def evaluate(): Unit =
    // Decide and mark under the monitor; send outside it. `message` is `None`
    // when there is nothing to page about, so the send below is skipped.
    val message: Option[(String, Instant)] = synchronized:
      current.lastSeen match
        // Rule 3: never pinged is disarmed. Rule 2: one page per outage.
        case Some(seen) if !current.alerted =>
          val elapsed = clock().getEpochSecond - seen.getEpochSecond
          if elapsed > config.deadlineSeconds then
            // Marked before sending; reverted below if the send does not land.
            persist(current.copy(alerted = true))
            Some((Messages.down(config.subject, elapsed, seen), seen))
          else None
        case _ => None

    message.foreach: (text, seenBefore) =>
      if !notifier.notify(text) then
        synchronized:
          // Retract only if nothing has moved since we marked: still alerted,
          // and the same lastSeen. A ping mid-send has already cleared the
          // alert, and re-arming would page for an outage that is over.
          if current.alerted && current.lastSeen.contains(seenBefore) then
            persist(current.copy(alerted = false))
            System.err.println("down page failed to send; will retry next tick")

  /** Swap in a new state and persist it, in that order, under the monitor.
    *
    * The write is deliberately inside the lock: "every state change is
    * persisted" is only true if no tick can slip between the mutation and the
    * save. It blocks on an fsync, but a plain worker thread is the right place
    * to block — unlike Python's event loop, which had to hand this to a thread.
    */
  private def persist(next: State): Unit =
    current = next
    State.save(next, config.stateFile)
