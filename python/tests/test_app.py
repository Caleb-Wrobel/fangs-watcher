"""The HTTP surface. SPEC.md § HTTP surface and § Security."""

from pathlib import Path

import pytest
from starlette.testclient import TestClient

from watcher.app import build_app
from watcher.config import Settings

TOKEN = "a-token-for-tests"


@pytest.fixture
def client(tmp_path: Path):
    settings = Settings(
        token=TOKEN,  # type: ignore[arg-type]
        discord_webhook="http://127.0.0.1:1/hook",  # type: ignore[arg-type]
        subject="limen",
        period_seconds=3600,  # long enough that the ticker never fires in a test
        grace_seconds=3600,
        state_file=tmp_path / "state.json",
    )
    with TestClient(build_app(settings)) as client:
        yield client


def test_healthz_is_200(client: TestClient) -> None:
    assert client.get("/healthz").status_code == 200


def test_ping_with_the_right_token_is_accepted(client: TestClient) -> None:
    assert client.post(f"/ping/{TOKEN}").status_code == 200


def test_ping_with_a_wrong_token_is_404(client: TestClient) -> None:
    """404, never 401 or 403 — a wrong token is not a hint."""
    assert client.post("/ping/not-the-token").status_code == 404


@pytest.mark.parametrize(
    "path",
    ["/ping/", "/ping", f"/PING/{TOKEN}", f"/ping/{TOKEN}/extra", f"/ping/{TOKEN.upper()}"],
)
def test_near_miss_paths_are_404(client: TestClient, path: str) -> None:
    assert client.post(path).status_code == 404


def test_wrong_method_is_indistinguishable_from_an_unknown_path(client: TestClient) -> None:
    """A 405 on the ping route would confirm it exists whatever the token."""
    assert client.get(f"/ping/{TOKEN}").status_code == 404
    assert client.get("/no-such-route").status_code == 404
    assert client.post("/healthz").status_code == 404


def test_no_other_routes(client: TestClient) -> None:
    for path in ["/", "/metrics", "/state", "/watcher-state.json"]:
        assert client.get(path).status_code == 404, path


def test_ping_persists_state(client: TestClient, tmp_path: Path) -> None:
    client.post(f"/ping/{TOKEN}")

    from watcher.state import State

    state = State.load(tmp_path / "state.json")
    assert state.last_seen is not None
    assert state.alerted is False
