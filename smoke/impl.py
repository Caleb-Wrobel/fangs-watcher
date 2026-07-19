"""Discovering and running an implementation.

The harness stays language-agnostic by knowing nothing about any impl beyond
what that impl's own `impl.json` manifest declares. Adding a language to this
repo means adding a directory with a manifest — never editing the harness.
"""

import json
import os
import signal
import socket
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.error import URLError
from urllib.request import urlopen

REPO_ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Manifest:
    """A `<impl>/impl.json`: how to build and run one implementation."""

    name: str
    language: str
    run: list[str]
    directory: Path
    build: list[str] | None = None
    ready_timeout: float = 90.0

    @staticmethod
    def load(path: Path) -> "Manifest":
        raw = json.loads(path.read_text())
        missing = {"language", "run"} - raw.keys()
        if missing:
            raise ValueError(f"{path}: manifest is missing {sorted(missing)}")
        if not isinstance(raw["run"], list) or not raw["run"]:
            raise ValueError(f"{path}: 'run' must be a non-empty argv list")
        return Manifest(
            name=raw.get("name", path.parent.name),
            language=raw["language"],
            run=raw["run"],
            directory=path.parent,
            build=raw.get("build"),
            # A cold scala-cli/JVM start is slow; impls may raise their own bar.
            ready_timeout=float(raw.get("ready_timeout_seconds", 90)),
        )


def discover(root: Path = REPO_ROOT) -> list[Manifest]:
    """Every impl.json in a direct subdirectory of the repo, name-sorted."""
    return sorted(
        (Manifest.load(p) for p in root.glob("*/impl.json")),
        key=lambda m: m.name,
    )


def routable_address() -> str | None:
    """This host's non-loopback address, or None if it has only loopback.

    Used to prove a watcher bound to 127.0.0.1 is *not* reachable off-loopback.
    The UDP connect is routing-table arithmetic — no packet is sent.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 9))  # TEST-NET-1: reserved, never routed
            address = s.getsockname()[0]
        except OSError:
            return None
    return None if address.startswith("127.") else address


def port_is_open(host: str, port: int, timeout: float = 2.0) -> bool:
    """Can we complete a TCP handshake to host:port?"""
    with socket.socket() as s:
        s.settimeout(timeout)
        try:
            s.connect((host, port))
            return True
        except OSError:
            return False


def free_port() -> int:
    """Ask the kernel for an unused port. Racy in principle, fine in practice."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Watcher:
    """A running implementation process, startable and stoppable repeatedly.

    Restart-behaviour is part of the contract (SPEC rule 4), so the harness
    needs to stop and start the same impl against the same statefile — this
    holds the config steady across those lifecycles.
    """

    def __init__(self, manifest: Manifest, env: dict[str, str], port: int):
        self.manifest = manifest
        self.env = env
        self.port = port
        self.process: subprocess.Popen | None = None

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def build(self) -> None:
        if not self.manifest.build:
            return
        print(f"  building ({' '.join(self.manifest.build)}) …", flush=True)
        result = subprocess.run(
            self.manifest.build,
            cwd=self.manifest.directory,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"build failed ({result.returncode}):\n{result.stdout}\n{result.stderr}"
            )

    def start(self) -> None:
        if self.process is not None:
            raise RuntimeError("already started")
        self.process = subprocess.Popen(
            self.manifest.run,
            cwd=self.manifest.directory,
            env={**os.environ, **self.env},
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            # Own process group: scala-cli and friends spawn a JVM child, and
            # killing only the launcher would orphan the port-holder.
            start_new_session=True,
        )
        self._await_healthz()

    def stop(self) -> None:
        """SIGTERM the whole group, then SIGKILL anything that lingers."""
        if self.process is None:
            return
        group = os.getpgid(self.process.pid)
        try:
            os.killpg(group, signal.SIGTERM)
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(group, signal.SIGKILL)
            self.process.wait(timeout=10)
        except ProcessLookupError:
            pass  # already gone
        finally:
            self.process = None

    def logs(self) -> str:
        """Drain whatever the process wrote. Only useful after it has exited."""
        if self.process is None or self.process.stdout is None:
            return ""
        return self.process.stdout.read() or ""

    def _await_healthz(self) -> None:
        """Block until GET /healthz answers 200, or the process dies trying."""
        deadline = time.monotonic() + self.manifest.ready_timeout
        while time.monotonic() < deadline:
            if self.process is not None and self.process.poll() is not None:
                raise RuntimeError(
                    f"{self.manifest.name} exited during startup "
                    f"(code {self.process.returncode}):\n{self.logs()}"
                )
            try:
                with urlopen(f"{self.base_url}/healthz", timeout=2) as response:
                    if response.status == 200:
                        return
            except (URLError, OSError, TimeoutError):
                pass
            time.sleep(0.2)
        raise RuntimeError(
            f"{self.manifest.name} did not serve /healthz within "
            f"{self.manifest.ready_timeout:.0f}s"
        )

    def __enter__(self) -> "Watcher":
        self.start()
        return self

    def __exit__(self, *_exc) -> None:
        self.stop()


def eprint(*args) -> None:
    print(*args, file=sys.stderr, flush=True)
