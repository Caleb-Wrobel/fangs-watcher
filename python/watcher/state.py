"""The two fields of persisted state, and the atomic write that protects them.

SPEC.md § Statefile: JSON you can `cat` during an incident, written atomically
so that a crash mid-write leaves the previous complete state rather than a torn
file. Absent file means fresh and disarmed.
"""

import json
import logging
import os
import tempfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

log = logging.getLogger(__name__)


@dataclass(slots=True)
class State:
    """`last_seen` absent means never pinged — and so, disarmed (SPEC rule 3)."""

    last_seen: datetime | None = None
    alerted: bool = False

    def to_json(self) -> str:
        return json.dumps(
            {
                "last_seen": self.last_seen.isoformat() if self.last_seen else None,
                "alerted": self.alerted,
            },
            indent=2,
        )

    @staticmethod
    def load(path: Path) -> "State":
        """Read state, treating an absent or unreadable file as fresh.

        A corrupt statefile is deliberately not fatal: refusing to boot would
        take the watcher down for good over a file it can simply rewrite. It
        re-arms on the next ping, which is the safe direction to fail — a
        watcher that is briefly disarmed is better than one that is not running.
        """
        try:
            raw = json.loads(path.read_text())
        except FileNotFoundError:
            log.info("no statefile at %s — starting fresh and disarmed", path)
            return State()
        except (OSError, json.JSONDecodeError) as e:
            log.warning("unreadable statefile at %s (%s) — starting fresh", path, e)
            return State()

        last_seen_raw = raw.get("last_seen")
        try:
            last_seen = datetime.fromisoformat(last_seen_raw) if last_seen_raw else None
        except (TypeError, ValueError):
            log.warning("bad last_seen %r in statefile — treating as absent", last_seen_raw)
            last_seen = None

        state = State(last_seen=last_seen, alerted=bool(raw.get("alerted", False)))
        log.info("loaded state: last_seen=%s alerted=%s", state.last_seen, state.alerted)
        return state

    def save(self, path: Path) -> None:
        """Write atomically: temp file in the same dir → fsync → rename.

        Same directory matters — `os.replace` is only atomic within a
        filesystem. The directory fsync is what actually makes the rename
        durable across a power loss; without it the rename can still be lost.
        """
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "w") as f:
                f.write(self.to_json())
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, path)
            self._fsync_dir(path.parent)
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise

    @staticmethod
    def _fsync_dir(directory: Path) -> None:
        # Not portable to Windows, where directories cannot be opened; the
        # watcher targets Linux containers, and a missed fsync here is not
        # worth failing the write over.
        try:
            fd = os.open(directory, os.O_RDONLY)
        except OSError:
            return
        try:
            os.fsync(fd)
        except OSError:
            pass
        finally:
            os.close(fd)
