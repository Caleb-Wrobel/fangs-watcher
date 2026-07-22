package watcher

/** Records what was sent, and can be told to start failing.
  *
  * Driving the webhook by hand is the only way to reach SPEC rule 5: the
  * end-to-end smoke test can make a webhook fail, but it cannot make one fail
  * *and* land a ping in the middle of the failure.
  */
final class FakeNotifier extends Notifier:
  private val sentBuf = collection.mutable.ArrayBuffer.empty[String]
  @volatile var working: Boolean = true
  @volatile var heartbeats: Int = 0

  /** Runs while a send is in flight — the hook the mid-send ping tests use. */
  @volatile var duringSend: () => Unit = () => ()

  def notify(content: String): Boolean = synchronized {
    duringSend()
    if !working then false
    else
      sentBuf += content
      true
  }

  def heartbeat(): Boolean = { heartbeats += 1; true }

  def sent: List[String] = synchronized(sentBuf.toList)
  def pages: List[String] = sent.filter(_.startsWith("🚨"))
  def recoveries: List[String] = sent.filter(_.startsWith("✅"))
