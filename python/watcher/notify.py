"""Outbound calls: the notification webhook and the healthchecks.io heartbeat.

SPEC.md § Notifications: a notification is a POST of `{"content": "<message>"}`
in Discord's shape. A failed POST is logged and never crashes the watcher — the
caller decides whether to retry, since only it knows if the page still applies.
"""

import logging
from datetime import datetime

import httpx

log = logging.getLogger(__name__)

# Generous enough for a slow webhook, short enough that a black-holed endpoint
# cannot wedge a ticker whose period may be only a few seconds.
TIMEOUT = httpx.Timeout(10.0, connect=5.0)


def human_duration(seconds: float) -> str:
    """A duration as an on-call human reads it: `45s`, `18m`, `2h 05m`."""
    seconds = int(max(seconds, 0))
    if seconds < 60:
        return f"{seconds}s"
    if seconds < 3600:
        return f"{seconds // 60}m"
    return f"{seconds // 3600}h {(seconds % 3600) // 60:02d}m"


def down_message(subject: str, elapsed: float, last_seen: datetime) -> str:
    return (
        f"🚨 {subject} is dark — no check-in for {human_duration(elapsed)}. "
        f"Last check-in: {last_seen.isoformat()}."
    )


def recovery_message(subject: str, now: datetime) -> str:
    return f"✅ {subject} is back — check-in resumed at {now.isoformat()}."


class Notifier:
    """Talks to the outside world. Every method reports success as a bool."""

    def __init__(self, webhook_url: str, healthcheck_url: str | None = None):
        self._webhook_url = webhook_url
        self._healthcheck_url = healthcheck_url
        self._client = httpx.AsyncClient(timeout=TIMEOUT)

    async def notify(self, content: str) -> bool:
        """POST a notification. Returns whether it landed.

        The URL is never logged: it embeds a credential (SPEC.md § Configuration).
        """
        try:
            response = await self._client.post(self._webhook_url, json={"content": content})
            response.raise_for_status()
        except httpx.HTTPError as e:
            log.error("notification failed (%s): %s", type(e).__name__, content)
            return False
        log.info("notified: %s", content)
        return True

    async def heartbeat(self) -> bool:
        """GET the healthchecks.io check, so the managed floor watches us too.

        Unset URL means the feature is off, which is a success: nothing was
        meant to happen and nothing failed.
        """
        if not self._healthcheck_url:
            return True
        try:
            response = await self._client.get(self._healthcheck_url)
            response.raise_for_status()
        except httpx.HTTPError as e:
            log.warning("self-heartbeat failed (%s)", type(e).__name__)
            return False
        return True

    async def aclose(self) -> None:
        await self._client.aclose()
