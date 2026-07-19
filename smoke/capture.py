"""The capture server: stands in for Discord and healthchecks.io.

The watcher under test does not know it isn't talking to the real services — it
POSTs notifications at /hook and GETs its self-heartbeat at /hc, and we record
both with arrival timestamps so the harness can assert on ordering and counts.
"""

import json
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from time import monotonic


@dataclass
class Notification:
    """One webhook POST, as the watcher sent it."""

    content: str
    at: float


@dataclass
class Capture:
    """Everything the fake outside world has seen."""

    notifications: list[Notification] = field(default_factory=list)
    heartbeats: list[float] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def record_notification(self, content: str) -> None:
        with self._lock:
            self.notifications.append(Notification(content, monotonic()))

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
        with self._lock:
            self.notifications.clear()
            self.heartbeats.clear()


class _Handler(BaseHTTPRequestHandler):
    capture: Capture  # injected per-server below

    def do_POST(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler's casing
        if self.path != "/hook":
            self._respond(404)
            return
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")
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
