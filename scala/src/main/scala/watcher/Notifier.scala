package watcher

import java.time.Instant

/** Outbound calls: the notification webhook and the healthchecks.io heartbeat.
  *
  * SPEC.md § Notifications: a notification is a POST of `{"content": "<message>"}`
  * in Discord's shape. A failed POST is logged and never crashes the watcher —
  * the caller decides whether to retry, since only it knows if the page still
  * applies.
  *
  * A trait, not a class, so the rules can be tested against a recording double
  * that can be told to start failing. That is the only way to reach SPEC rule 5.
  */
trait Notifier:

  /** POST a notification. Returns whether it landed. */
  def notify(content: String): Boolean

  /** GET the healthchecks.io check, so the managed floor watches us too.
    *
    * An unset URL means the feature is off, which is a success: nothing was
    * meant to happen and nothing failed.
    */
  def heartbeat(): Boolean

/** The wording of the two messages, and the duration format they embed.
  *
  * Separate from the transport so the exact strings can be asserted without a
  * socket. The smoke test deliberately does not pin `{elapsed}`'s format, so
  * this is the only place it is checked.
  */
object Messages:

  /** A duration as an on-call human reads it: `45s`, `18m`, `2h 05m`. */
  def humanDuration(seconds: Long): String = ???

  /** `🚨 {subject} is dark — no check-in for {elapsed}. Last check-in: {last_seen}.` */
  def down(subject: String, elapsedSeconds: Long, lastSeen: Instant): String = ???

  /** `✅ {subject} is back — check-in resumed at {now}.` */
  def recovery(subject: String, now: Instant): String = ???

/** Talks to the outside world over blocking HTTP.
  *
  * Timeouts are load-bearing: a black-holed webhook must not wedge a scheduler
  * whose period may be only a few seconds.
  */
final class HttpNotifier(webhookUrl: String, healthcheckUrl: Option[String]) extends Notifier:

  /** The URL is never logged: it embeds a credential (SPEC.md § Configuration). */
  def notify(content: String): Boolean = ???

  def heartbeat(): Boolean = ???
