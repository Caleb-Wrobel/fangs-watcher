"""The contract itself: SPEC.md § The rules, and nothing about HTTP.

Kept free of Starlette so the rules can be read — and tested — on their own.
"""

import asyncio
import logging
from datetime import UTC, datetime

import anyio

from .config import Settings
from .notify import Notifier, down_message, recovery_message
from .state import State

log = logging.getLogger(__name__)


def utcnow() -> datetime:
    return datetime.now(UTC)


class Watcher:
    """Owns the state and the two rules that change it.

    The lock is held only across in-memory mutation and the statefile write,
    never across a network call — a wedged webhook must not block a ping.
    """

    def __init__(self, settings: Settings, notifier: Notifier, state: State):
        self.settings = settings
        self.notifier = notifier
        self.state = state
        self._lock = asyncio.Lock()

    async def _persist(self) -> None:
        """Write the statefile off the event loop; it fsyncs, so it blocks."""
        await anyio.to_thread.run_sync(self.state.save, self.settings.state_file)

    async def record_ping(self) -> datetime | None:
        """SPEC rule 1: an accepted ping refreshes `last_seen` and clears any
        outstanding alert.

        Returns the timestamp to announce a recovery for, or None if no alert
        was outstanding. The announcement is left to the caller so the ping can
        be acknowledged without waiting on the webhook.
        """
        async with self._lock:
            now = utcnow()
            self.state.last_seen = now
            was_alerted = self.state.alerted
            self.state.alerted = False
            await self._persist()
        return now if was_alerted else None

    async def announce_recovery(self, at: datetime) -> None:
        """Send the recovery notice, after the ping has already been answered."""
        if await self.notifier.notify(recovery_message(self.settings.subject, at)):
            return
        # The page is still outstanding as far as anyone watching knows, so say
        # so — and let the next ping try the recovery again.
        async with self._lock:
            if self.state.last_seen == at:
                self.state.alerted = True
                await self._persist()
                log.warning("recovery failed to send; will retry on the next ping")

    async def on_tick(self) -> None:
        """One ticker tick: heartbeat, then SPEC rule 2.

        Runs every `period`. Never raises — a tick that dies takes the whole
        dead-man's switch with it.
        """
        try:
            await self.notifier.heartbeat()
            await self._evaluate()
        except Exception:
            log.exception("tick failed; continuing")

    async def _evaluate(self) -> None:
        async with self._lock:
            last_seen = self.state.last_seen

            # Rule 3: never pinged means disarmed. Rule 2: one page per outage.
            if last_seen is None or self.state.alerted:
                return

            elapsed = (utcnow() - last_seen).total_seconds()
            if elapsed <= self.settings.deadline_seconds:
                return

            # Marked before sending, so a slow webhook cannot let the next tick
            # page a second time. Reverted below if the send does not land.
            self.state.alerted = True
            await self._persist()
            message = down_message(self.settings.subject, elapsed, last_seen)

        if await self.notifier.notify(message):
            return

        async with self._lock:
            # Only retract the alert if nothing has happened since — a ping that
            # arrived mid-send has already cleared it, and re-arming here would
            # page for an outage that is over.
            if self.state.alerted and self.state.last_seen == last_seen:
                self.state.alerted = False
                await self._persist()
                log.warning("down page failed to send; will retry next tick")

    async def run_ticker(self) -> None:
        """Tick forever, on the period. Cancelled at shutdown."""
        log.info(
            "ticker running: period=%ss grace=%ss (dark after %ss of silence)",
            self.settings.period_seconds,
            self.settings.grace_seconds,
            self.settings.deadline_seconds,
        )
        while True:
            await asyncio.sleep(self.settings.period_seconds)
            await self.on_tick()
