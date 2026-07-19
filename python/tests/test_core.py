"""The rules, in isolation. SPEC.md § The rules.

These drive the clock and the webhook by hand, so they can assert the paths the
end-to-end smoke test cannot force: a notification that fails to send, and a
ping that lands while a page is mid-flight.
"""

from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from watcher.config import Settings
from watcher.core import Watcher
from watcher.state import State

PERIOD, GRACE = 10, 20
DEADLINE = PERIOD + GRACE


class FakeNotifier:
    """Records what was sent, and can be told to start failing."""

    def __init__(self) -> None:
        self.sent: list[str] = []
        self.heartbeats = 0
        self.working = True

    async def notify(self, content: str) -> bool:
        if not self.working:
            return False
        self.sent.append(content)
        return True

    async def heartbeat(self) -> bool:
        self.heartbeats += 1
        return True

    @property
    def pages(self) -> list[str]:
        return [s for s in self.sent if s.startswith("🚨")]

    @property
    def recoveries(self) -> list[str]:
        return [s for s in self.sent if s.startswith("✅")]


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        token="t",  # type: ignore[arg-type]
        discord_webhook="http://localhost/hook",  # type: ignore[arg-type]
        subject="limen",
        period_seconds=PERIOD,
        grace_seconds=GRACE,
        state_file=tmp_path / "state.json",
    )


def make(settings: Settings, state: State | None = None) -> tuple[Watcher, FakeNotifier]:
    notifier = FakeNotifier()
    return Watcher(settings, notifier, state or State()), notifier  # type: ignore[arg-type]


def ago(seconds: float) -> datetime:
    return datetime.now(UTC) - timedelta(seconds=seconds)


# ── rule 3: disarmed until the first ping ─────────────────────────────────


async def test_never_pinged_never_pages(settings: Settings) -> None:
    watcher, notifier = make(settings)
    for _ in range(5):
        await watcher.on_tick()
    assert notifier.sent == []


async def test_ticks_still_heartbeat_while_disarmed(settings: Settings) -> None:
    """The self-heartbeat is not gated on being armed — a disarmed watcher is
    still alive, and healthchecks.io must keep hearing so."""
    watcher, notifier = make(settings)
    await watcher.on_tick()
    assert notifier.heartbeats == 1


# ── rule 2: page once when the subject goes dark ──────────────────────────


async def test_silence_within_deadline_is_tolerated(settings: Settings) -> None:
    watcher, notifier = make(settings, State(last_seen=ago(DEADLINE - 1)))
    await watcher.on_tick()
    assert notifier.pages == []


async def test_pages_once_past_deadline(settings: Settings) -> None:
    watcher, notifier = make(settings, State(last_seen=ago(DEADLINE + 5)))
    for _ in range(4):
        await watcher.on_tick()

    assert len(notifier.pages) == 1, "one outage, one page"
    assert notifier.pages[0].startswith("🚨 limen is dark")
    assert watcher.state.alerted is True


async def test_alerted_state_survives_reload(settings: Settings) -> None:
    """Rule 4: a restart mid-outage must not re-page."""
    watcher, _ = make(settings, State(last_seen=ago(DEADLINE + 5)))
    await watcher.on_tick()

    restarted, notifier = make(settings, State.load(settings.state_file))
    assert restarted.state.alerted is True
    await restarted.on_tick()
    assert notifier.pages == [], "already paged before the restart"


# ── rule 1: recovery on the next ping ─────────────────────────────────────


async def test_ping_clears_alert_and_announces(settings: Settings) -> None:
    watcher, notifier = make(settings, State(last_seen=ago(DEADLINE + 5)))
    await watcher.on_tick()

    recovered_at = await watcher.record_ping()
    assert recovered_at is not None
    await watcher.announce_recovery(recovered_at)

    assert len(notifier.recoveries) == 1
    assert notifier.recoveries[0].startswith("✅ limen is back")
    assert watcher.state.alerted is False


async def test_ping_without_outstanding_alert_is_silent(settings: Settings) -> None:
    watcher, notifier = make(settings, State(last_seen=ago(1)))
    assert await watcher.record_ping() is None
    assert notifier.sent == []


# ── failure handling: SPEC says log and retry, never crash ────────────────


async def test_failed_page_is_retried_next_tick(settings: Settings) -> None:
    watcher, notifier = make(settings, State(last_seen=ago(DEADLINE + 5)))
    notifier.working = False

    await watcher.on_tick()
    assert notifier.pages == []
    assert watcher.state.alerted is False, "must not claim an alert that never sent"

    notifier.working = True
    await watcher.on_tick()
    assert len(notifier.pages) == 1, "the outage is still applicable, so retry"


async def test_failed_recovery_is_retried_on_the_next_ping(settings: Settings) -> None:
    watcher, notifier = make(settings, State(last_seen=ago(DEADLINE + 5)))
    await watcher.on_tick()

    notifier.working = False
    first = await watcher.record_ping()
    assert first is not None
    await watcher.announce_recovery(first)
    assert watcher.state.alerted is True, "page is still outstanding to anyone watching"

    notifier.working = True
    second = await watcher.record_ping()
    assert second is not None, "the retry must be offered again"
    await watcher.announce_recovery(second)
    assert len(notifier.recoveries) == 1
    assert watcher.state.alerted is False


async def test_ping_during_a_failing_page_wins(settings: Settings) -> None:
    """A check-in that lands while a down page is mid-flight takes precedence.

    The tick marks `alerted` before sending, so a failed send tries to undo it.
    That rollback must not clobber a ping that arrived in the meantime: the
    subject is demonstrably alive and the page never reached anyone, so the
    watcher must end up disarmed with the ping's timestamp intact.
    """
    watcher, notifier = make(settings, State(last_seen=ago(DEADLINE + 5)))
    notifier.working = False

    pinged_at = None

    async def ping_mid_send(_content: str) -> bool:
        nonlocal pinged_at
        pinged_at = await watcher.record_ping()
        return False

    notifier.notify = ping_mid_send  # type: ignore[method-assign]
    await watcher.on_tick()

    assert watcher.state.alerted is False, "the subject is alive and no page was delivered"
    assert watcher.state.last_seen == pinged_at, "the ping's check-in must not be rolled back"


async def test_tick_survives_an_exploding_notifier(settings: Settings) -> None:
    """A tick that raises would silently kill the dead-man's switch."""
    watcher, notifier = make(settings, State(last_seen=ago(DEADLINE + 5)))

    async def explode(_content: str) -> bool:
        raise RuntimeError("webhook exploded")

    notifier.notify = explode  # type: ignore[method-assign]
    await watcher.on_tick()  # must not raise

    notifier.notify = FakeNotifier.notify.__get__(notifier)  # type: ignore[method-assign]
    notifier.working = True
    await watcher.on_tick()
