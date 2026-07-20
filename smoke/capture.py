"""The capture server: stands in for Discord and healthchecks.io.

The watcher under test does not know it isn't talking to the real services — it
POSTs notifications at /hook and GETs its self-heartbeat at /hc, and we record
both with arrival timestamps so the harness can assert on ordering and counts.
"""

import json
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from time import monotonic, sleep


@dataclass
class Notification:
    """One webhook POST, as the watcher sent it."""

    content: str
    at: float


@dataclass
class Capture:
    """Everything the fake outside world has seen — and how it misbehaves.

    The fault knobs let the harness exercise SPEC rule 5 (a notification that
    fails to send is retried while it still applies), which is otherwise only
    reachable from inside an implementation's own test suite.
    """

    notifications: list[Notification] = field(default_factory=list)
    heartbeats: list[float] = field(default_factory=list)

    # Fault injection. `attempts` counts every webhook POST that arrived,
    # including the ones deliberately rejected — the distinction between
    # "attempted" and "delivered" is exactly what rule 5 turns on.
    attempts: int = 0
    fail_webhook: bool = False
    webhook_delay: float = 0.0

    _lock: threading.Lock = field(default_factory=threading.Lock)

    def record_notification(self, content: str) -> None:
        with self._lock:
            self.notifications.append(Notification(content, monotonic()))

    def begin_attempt(self) -> tuple[bool, float]:
        """Register an inbound webhook POST; report how to treat it."""
        with self._lock:
            self.attempts += 1
            return self.fail_webhook, self.webhook_delay

    def attempt_count(self) -> int:
        with self._lock:
            return self.attempts

    def inject_failure(self, *, delay: float = 0.0) -> None:
        """Reject webhook POSTs with a 500, optionally after hanging first."""
        with self._lock:
            self.fail_webhook = True
            self.webhook_delay = delay

    def heal(self) -> None:
        with self._lock:
            self.fail_webhook = False
            self.webhook_delay = 0.0

    def record_heartbeat(self) -> None:
        with self._lock:
            self.heartbeats.append(monotonic())

    def contents(self) -> list[str]:
        with self._lock:
            return [n.content for n in self.notifications]

    def down_pages(self) -> list[str]:
        return [c for c in self.contents() if c.startswith("🚨")]

    def recoveries(self) -> list[str]:
        return [c for c in self.contents() if c.startswith("✅")]

    def heartbeat_count(self) -> int:
        with self._lock:
            return len(self.heartbeats)

    def clear(self) -> None:
        """Forget what was seen. Deliberately leaves the fault knobs alone."""
        with self._lock:
            self.notifications.clear()
            self.heartbeats.clear()
            self.attempts = 0


class _Handler(BaseHTTPRequestHandler):
    capture: Capture  # injected per-server below

    def do_POST(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler's casing
        if self.path != "/hook":
            self._respond(404)
            return
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")

        # Read the body first, so a rejected attempt still consumes the request
        # exactly as a real webhook would.
        should_fail, delay = self.capture.begin_attempt()
        if delay:
            # Hold the connection open, so the harness can slip a ping in while
            # the watcher is mid-send.
            sleep(delay)
        if should_fail:
            self._respond(500)
            return

        try:
            content = json.loads(raw).get("content", "")
        except json.JSONDecodeError:
            # A malformed body is a contract violation, not a crash: record it
            # verbatim so the failing assertion shows what actually arrived.
            content = f"<unparseable: {raw!r}>"
        self.capture.record_notification(content)
        self._respond(204)

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/hc":
            self._respond(404)
            return
        self.capture.record_heartbeat()
        self._respond(200)

    def _respond(self, code: int) -> None:
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *_args) -> None:
        """Silence the default stderr access log — the harness owns the output."""


def start_capture_server(port: int) -> tuple[Capture, ThreadingHTTPServer]:
    """Serve /hook and /hc on `port` in a background thread."""
    capture = Capture()
    handler = type("BoundHandler", (_Handler,), {"capture": capture})
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return capture, server
