"""Statefile durability. SPEC.md § Statefile."""

import json
from datetime import UTC, datetime
from pathlib import Path

import pytest

from watcher.state import State


def test_roundtrip(tmp_path: Path) -> None:
    path = tmp_path / "state.json"
    original = State(last_seen=datetime(2026, 7, 19, 12, 0, tzinfo=UTC), alerted=True)
    original.save(path)

    loaded = State.load(path)
    assert loaded.last_seen == original.last_seen
    assert loaded.alerted is True


def test_absent_file_is_fresh_and_disarmed(tmp_path: Path) -> None:
    """Rule 3: no statefile means never pinged, which means never page."""
    state = State.load(tmp_path / "nothing-here.json")
    assert state.last_seen is None
    assert state.alerted is False


def test_corrupt_file_degrades_to_fresh(tmp_path: Path) -> None:
    """A truncated statefile must not stop the watcher from booting."""
    path = tmp_path / "state.json"
    path.write_text('{"last_seen": "2026-07-19T12:00:00+00:0')

    state = State.load(path)
    assert state.last_seen is None
    assert state.alerted is False


def test_unparseable_timestamp_is_treated_as_absent(tmp_path: Path) -> None:
    path = tmp_path / "state.json"
    path.write_text('{"last_seen": "yesterday-ish", "alerted": true}')

    assert State.load(path).last_seen is None


def test_is_human_readable(tmp_path: Path) -> None:
    """You will `cat` this during an incident."""
    path = tmp_path / "state.json"
    State(last_seen=datetime(2026, 7, 19, tzinfo=UTC), alerted=False).save(path)

    text = path.read_text()
    assert "\n" in text, "should be indented, not one line"
    assert json.loads(text).keys() == {"last_seen", "alerted"}, "two fields, nothing else"


def test_write_leaves_no_temp_files(tmp_path: Path) -> None:
    """The temp file is renamed over the target, never left beside it."""
    path = tmp_path / "state.json"
    for _ in range(3):
        State(last_seen=datetime.now(UTC)).save(path)

    assert [p.name for p in tmp_path.iterdir()] == ["state.json"]


def test_overwrite_is_atomic(tmp_path: Path, monkeypatch) -> None:
    """A crash mid-write leaves the previous complete state, never a torn file.

    Simulated by failing the write after the temp file exists but before the
    rename — the observable guarantee is that the original file is intact.
    """
    path = tmp_path / "state.json"
    good = State(last_seen=datetime(2026, 7, 19, tzinfo=UTC), alerted=False)
    good.save(path)

    def explode(*_args, **_kwargs):
        raise OSError("disk full, mid-write")

    monkeypatch.setattr("os.replace", explode)
    with pytest.raises(OSError, match="disk full"):
        State(last_seen=datetime(2026, 7, 20, tzinfo=UTC), alerted=True).save(path)

    survivor = State.load(path)
    assert survivor.last_seen == good.last_seen, "previous state must survive"
    assert survivor.alerted is False
    assert [p.name for p in tmp_path.iterdir()] == ["state.json"], "temp file cleaned up"
