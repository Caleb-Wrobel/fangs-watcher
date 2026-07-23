#!/usr/bin/env python3
"""The shared smoke test: does an implementation satisfy SPEC.md?

Every impl in this repo is checked by this one harness against the one contract.
The numbered checks below correspond directly to SPEC.md § "The smoke test".

    ./smoke/smoke.py            # every impl with a manifest
    ./smoke/smoke.py scala      # just this one
    ./smoke/smoke.py --list     # what's discoverable

Standard library only, on purpose: cloning the repo should be enough to run it.
"""

import argparse
import json
import sys
import threading
import tempfile
import time
from contextlib import suppress
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))

from capture import Capture, start_capture_server  # noqa: E402
from impl import (  # noqa: E402
    Manifest,
    Watcher,
    discover,
    eprint,
    free_port,
    port_is_open,
    routable_address,
)

# Deliberately tiny, so an outage is a few seconds rather than a few minutes.
# Down is declared once now - last_seen > PERIOD + GRACE, evaluated on ticks.
PERIOD_SECONDS = 2
GRACE_SECONDS = 2
OUTAGE_THRESHOLD = PERIOD_SECONDS + GRACE_SECONDS
TOKEN = "smoke-token-not-a-secret"
WRONG_TOKEN = "smoke-token-not-a-secretx"
SUBJECT = "limen"

# How long to wait for a page that should arrive, and how long to keep watching
# to be convinced one that shouldn't arrive really doesn't.
EXPECT_TIMEOUT = OUTAGE_THRESHOLD + 4 * PERIOD_SECONDS + 5
QUIET_WINDOW = OUTAGE_THRESHOLD + 2 * PERIOD_SECONDS


class CheckFailed(AssertionError):
    """A contract violation, phrased for someone reading a red test."""


@dataclass
class Result:
    name: str
    passed: bool
    detail: str = ""


# ─────────────────────────────── assertions ───────────────────────────────


def http_status(url: str, method: str = "GET") -> int:
    """The status code, with error responses treated as data rather than raised."""
    try:
        with urlopen(Request(url, method=method), timeout=5) as response:
            return response.status
    except HTTPError as e:
        return e.code


def ping(watcher: Watcher, token: str = TOKEN) -> int:
    return http_status(f"{watcher.base_url}/ping/{token}", method="POST")


def read_state(path: Path) -> dict:
    if not path.exists():
        raise CheckFailed(f"statefile {path} does not exist")
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError as e:
        raise CheckFailed(f"statefile is not valid JSON ({e}): {path.read_text()!r}")


def wait_until(predicate: Callable[[], bool], timeout: float, what: str) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise CheckFailed(f"timed out after {timeout:.0f}s waiting for {what}")


def stay_quiet(capture: Capture, seconds: float, what: str) -> None:
    """Assert nothing is sent for `seconds` — the absence half of the contract."""
    before = len(capture.contents())
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        now = capture.contents()
        if len(now) > before:
            raise CheckFailed(f"expected silence while {what}, but got: {now[before:]}")
        time.sleep(0.1)


# ─────────────────────────────── the checks ───────────────────────────────


def run_checks(manifest: Manifest, capture: Capture, capture_port: int) -> list[Result]:
    """SPEC.md § The smoke test, in order. Later checks build on earlier state."""
    results: list[Result] = []
    workdir = Path(tempfile.mkdtemp(prefix=f"smoke-{manifest.name}-"))
    statefile = workdir / "watcher-state.json"
    env = {
        "WATCHER_TOKEN": TOKEN,
        "WATCHER_DISCORD_WEBHOOK": f"http://127.0.0.1:{capture_port}/hook",
        "WATCHER_HEALTHCHECK_URL": f"http://127.0.0.1:{capture_port}/hc",
        "WATCHER_SUBJECT": SUBJECT,
        "WATCHER_PERIOD_SECONDS": str(PERIOD_SECONDS),
        "WATCHER_GRACE_SECONDS": str(GRACE_SECONDS),
        "WATCHER_STATE_FILE": str(statefile),
        "WATCHER_BIND": "127.0.0.1",
        "WATCHER_PORT": "",  # filled in per-boot below
    }

    def boot() -> Watcher:
        port = free_port()
        return Watcher(manifest, {**env, "WATCHER_PORT": str(port)}, port)

    def check(name: str, body: Callable[[], str | None]) -> bool:
        try:
            detail = body() or ""
            results.append(Result(name, True, detail))
            print(f"  ✓ {name}" + (f" — {detail}" if detail else ""), flush=True)
            return True
        except CheckFailed as e:
            results.append(Result(name, False, str(e)))
            print(f"  ✗ {name}\n      {e}", flush=True)
            return False

    watcher = boot()
    try:
        # ── 1. liveness ──────────────────────────────────────────────────
        # Reaching this point means /healthz already answered 200: Watcher.start
        # blocks on it. The check records that fact rather than re-deriving it.
        watcher.build()
        try:
            watcher.start()
        except RuntimeError as e:
            print(f"  ✗ boots and serves GET /healthz\n      {e}", flush=True)
            return [Result("boots and serves GET /healthz", False, str(e))]
        check("1. GET /healthz → 200", lambda: None)

        # ── the bind default fails closed ────────────────────────────────
        # Production runs this in a podman pod behind a networking sidecar that
        # reaches it over loopback. An impl that ignores WATCHER_BIND and listens
        # on 0.0.0.0 would publish /ping on the pod IP, bypassing that front door.
        def honours_bind() -> str:
            address = routable_address()
            if address is None:
                return "skipped — host has no non-loopback address"
            if port_is_open(address, watcher.port):
                raise CheckFailed(
                    f"WATCHER_BIND=127.0.0.1, but the watcher also accepts "
                    f"connections on {address}:{watcher.port} — it is listening on "
                    f"all interfaces. In production this exposes /ping on the pod IP."
                )
            return f"not reachable on {address} — loopback only"

        check("1b. honours WATCHER_BIND (loopback only)", honours_bind)

        # ── 3 (pre). a never-pinged watcher is disarmed ──────────────────
        # SPEC rule 3. Asserted before the first ping, while last_seen is still
        # absent — the only window in which this is observable.
        def disarmed() -> str:
            if statefile.exists():
                state = read_state(statefile)
                if state.get("last_seen") is not None:
                    raise CheckFailed(
                        f"never pinged, but last_seen is {state['last_seen']!r}"
                    )
            stay_quiet(capture, QUIET_WINDOW, "never having been pinged")
            return f"silent for {QUIET_WINDOW}s while disarmed"

        check("3. disarmed until first ping (rule 3)", disarmed)

        # ── 2. the ping endpoint ─────────────────────────────────────────
        def wrong_token_is_404() -> str:
            status = ping(watcher, WRONG_TOKEN)
            if status != 404:
                raise CheckFailed(f"POST /ping/<wrong> → {status}, want 404")
            return "wrong token reveals nothing"

        def right_token_records() -> str:
            status = ping(watcher)
            if status != 200:
                raise CheckFailed(f"POST /ping/<token> → {status}, want 200")
            wait_until(
                lambda: statefile.exists() and read_state(statefile).get("last_seen"),
                timeout=5,
                what="last_seen to be written to the statefile",
            )
            state = read_state(statefile)
            if state.get("alerted") is not False:
                raise CheckFailed(f"alerted should be false after first ping: {state}")
            return f"last_seen = {state['last_seen']}"

        def method_mismatch_is_404() -> str:
            """A 405 would confirm the route exists whatever token was tried."""
            unknown = http_status(f"{watcher.base_url}/no-such-route")
            if unknown != 404:
                raise CheckFailed(f"GET /no-such-route → {unknown}, want 404")
            for method, path in [("GET", f"/ping/{TOKEN}"), ("POST", "/healthz")]:
                status = http_status(f"{watcher.base_url}{path}", method=method)
                if status != 404:
                    raise CheckFailed(
                        f"{method} {path} → {status}, want 404 — a {status} here reveals "
                        f"the route exists regardless of the token (SPEC.md § HTTP surface)"
                    )
            return "wrong method is indistinguishable from an unknown path"

        check("2a. POST /ping/<wrong> → 404", wrong_token_is_404)
        check("2c. wrong method → 404, not 405", method_mismatch_is_404)
        check("2b. POST /ping/<token> → 200, state persisted", right_token_records)

        # ── the self-heartbeat ───────────────────────────────────────────
        def heartbeats() -> str:
            before = capture.heartbeat_count()
            wait_until(
                lambda: capture.heartbeat_count() > before,
                timeout=3 * PERIOD_SECONDS + 5,
                what="a GET to WATCHER_HEALTHCHECK_URL",
            )
            return f"{capture.heartbeat_count()} heartbeat(s) so far"

        check("healthchecks.io self-heartbeat on tick", heartbeats)

        # ── 4. going dark pages exactly once ─────────────────────────────
        def pages_when_dark() -> str:
            wait_until(
                lambda: len(capture.down_pages()) >= 1,
                timeout=EXPECT_TIMEOUT,
                what=f"a down page after {OUTAGE_THRESHOLD}s of silence",
            )
            page = capture.down_pages()[0]
            if not page.startswith(f"🚨 {SUBJECT} is dark"):
                raise CheckFailed(f"down page has the wrong shape: {page!r}")
            # It must not re-page every tick while still dark.
            time.sleep(3 * PERIOD_SECONDS)
            if len(capture.down_pages()) != 1:
                raise CheckFailed(
                    f"expected exactly one down page, got {len(capture.down_pages())}: "
                    f"{capture.down_pages()}"
                )
            wait_until(
                lambda: read_state(statefile).get("alerted") is True,
                timeout=5,
                what="alerted to be persisted as true",
            )
            return page

        check("4. pages once when check-ins stop", pages_when_dark)

        # ── 5. resuming pages recovery, exactly once ─────────────────────
        def recovers() -> str:
            status = ping(watcher)
            if status != 200:
                raise CheckFailed(f"POST /ping/<token> → {status}, want 200")
            wait_until(
                lambda: len(capture.recoveries()) >= 1,
                timeout=10,
                what="a recovery notice after check-ins resume",
            )
            notice = capture.recoveries()[0]
            if not notice.startswith(f"✅ {SUBJECT} is back"):
                raise CheckFailed(f"recovery has the wrong shape: {notice!r}")
            wait_until(
                lambda: read_state(statefile).get("alerted") is False,
                timeout=5,
                what="alerted to be cleared",
            )
            if len(capture.recoveries()) != 1:
                raise CheckFailed(f"expected one recovery: {capture.recoveries()}")
            return notice

        check("5. pages recovery when check-ins resume", recovers)

        # ── 6. restart during an outage does not duplicate the page ──────
        # SPEC rule 4: go dark again, let it page, then restart mid-outage.
        # The restarted process must load alerted=true and stay silent.
        def restart_mid_outage() -> str:
            wait_until(
                lambda: len(capture.down_pages()) >= 2,
                timeout=EXPECT_TIMEOUT,
                what="a second down page after going dark again",
            )
            state_before = read_state(statefile)
            if state_before.get("alerted") is not True:
                raise CheckFailed(f"expected alerted=true before restart: {state_before}")
            watcher.stop()
            capture.clear()
            fresh = boot()
            fresh.start()
            try:
                stay_quiet(capture, QUIET_WINDOW, "restarted while already alerted")
            finally:
                fresh.stop()
            return f"silent for {QUIET_WINDOW}s after restarting mid-outage"

        check("6. restart mid-outage does not re-page (rule 4)", restart_mid_outage)

        # ── 7. a fresh boot with no statefile pages nothing ──────────────
        def fresh_install_is_silent() -> str:
            statefile.unlink(missing_ok=True)
            capture.clear()
            fresh = boot()
            fresh.start()
            try:
                stay_quiet(capture, QUIET_WINDOW, "freshly installed and never pinged")
            finally:
                fresh.stop()
            return f"silent for {QUIET_WINDOW}s with no statefile"

        check("7. fresh install pages nothing (rule 3)", fresh_install_is_silent)

        # ── 8-10. rule 5: a failed notification is retried ────────────────
        # These need the outside world to misbehave on cue, which is why the
        # capture server can be told to fail. Each runs against a fresh boot.
        statefile.unlink(missing_ok=True)
        capture.clear()
        capture.heal()
        faulty = boot()
        faulty.start()
        try:

            def failed_page_is_retried() -> str:
                """A page that never sent must not silence the retry: the impl
                keeps attempting until the webhook recovers."""
                capture.inject_failure()
                if ping(faulty) != 200:
                    raise CheckFailed("could not arm the watcher")

                # Proof the retry is NOT suppressed: attempts keep climbing while
                # the webhook fails. A wrongly-latched alerted=true would stop at
                # one attempt and time this wait out. We deliberately do NOT read
                # the alerted flag here — mid-attempt it is transiently true by
                # SPEC's own leave (mark before send, roll back on failure), and
                # sampling that instant races the rollback's fsync. A GC pause
                # widens the window: flaky on the JVM, worse under native-image's
                # serial GC. Assert on latched, monotonic facts (attempts +
                # delivery), never on the instantaneous value of the flag.
                wait_until(
                    lambda: capture.attempt_count() >= 2,
                    timeout=EXPECT_TIMEOUT + 2 * PERIOD_SECONDS,
                    what="a down page to be attempted, and then retried",
                )
                if capture.down_pages():
                    raise CheckFailed("the webhook was failing, yet a page was recorded")

                attempts = capture.attempt_count()
                capture.heal()
                wait_until(
                    lambda: len(capture.down_pages()) >= 1,
                    timeout=EXPECT_TIMEOUT,
                    what="the down page to land once the webhook recovers",
                )
                return f"retried {attempts}x while failing, then delivered"

            check("8. a failed down page is retried (rule 5)", failed_page_is_retried)

            def failed_recovery_is_retried() -> str:
                """A recovery that never sent must leave `alerted` true."""
                wait_until(
                    lambda: read_state(statefile).get("alerted") is True,
                    timeout=5,
                    what="alerted to be true after the delivered page",
                )
                capture.inject_failure()
                before = capture.attempt_count()
                if ping(faulty) != 200:
                    raise CheckFailed("ping rejected")
                wait_until(
                    lambda: capture.attempt_count() > before,
                    timeout=10,
                    what="a recovery notice to be attempted",
                )
                # The re-arm is asynchronous (the ping's 200 must not wait on the
                # webhook), so wait for alerted to CONVERGE back to true rather
                # than sampling it once after a fixed sleep — that sample races the
                # clear->send->re-arm window, which a GC pause widens (flaky on the
                # JVM, worse under native-image). The latched fact that matters:
                # no recovery is ever delivered while the webhook is failing.
                wait_until(
                    lambda: read_state(statefile).get("alerted") is True,
                    timeout=EXPECT_TIMEOUT,
                    what="alerted to be re-armed after the recovery failed to send",
                )
                if capture.recoveries():
                    raise CheckFailed(
                        "a recovery was recorded as delivered while the webhook was "
                        "failing — the page is not really outstanding (SPEC rule 5)"
                    )

                capture.heal()
                if ping(faulty) != 200:
                    raise CheckFailed("ping rejected")
                wait_until(
                    lambda: len(capture.recoveries()) >= 1,
                    timeout=10,
                    what="the recovery to land on the next ping",
                )
                wait_until(
                    lambda: read_state(statefile).get("alerted") is False,
                    timeout=5,
                    what="alerted to clear once the recovery lands",
                )
                return "held the alert until the recovery actually sent"

            check("9. a failed recovery is retried (rule 5)", failed_recovery_is_retried)

            def ping_beats_a_failing_page() -> str:
                """A check-in during a failing page wins: the subject is alive.

                The webhook is made to hang and then fail, so there is a window
                in which the watcher is mid-send and a ping can land.
                """
                capture.clear()
                if ping(faulty) != 200:
                    raise CheckFailed("could not arm the watcher")
                armed_at = read_state(statefile).get("last_seen")

                hang = 3 * PERIOD_SECONDS
                capture.inject_failure(delay=hang)
                wait_until(
                    lambda: capture.attempt_count() >= 1,
                    timeout=EXPECT_TIMEOUT,
                    what="the watcher to start sending a down page",
                )
                # It is now blocked in the webhook. Check in.
                if ping(faulty) != 200:
                    raise CheckFailed("ping rejected mid-send")
                capture.heal()
                time.sleep(hang)

                state = read_state(statefile)
                if state.get("alerted") is True:
                    raise CheckFailed(
                        "alerted=true after a ping landed mid-page — the subject checked "
                        "in and the page never sent, so the watcher must be disarmed "
                        "(SPEC rule 5)"
                    )
                if state.get("last_seen") == armed_at:
                    raise CheckFailed(
                        "the mid-send check-in was rolled back along with the failed "
                        f"page — last_seen is still {armed_at}, losing a ping the "
                        "watcher already acknowledged with a 200"
                    )

                # Keep checking in while we watch. Going quiet here would earn a
                # perfectly legitimate second page and prove nothing — the point
                # is that the *failed* page does not resurface for a live subject.
                # A recovery notice is allowed: the impl may have marked itself
                # alerted before sending, and announcing the all-clear is honest.
                deadline = time.monotonic() + QUIET_WINDOW
                while time.monotonic() < deadline:
                    if ping(faulty) != 200:
                        raise CheckFailed("ping rejected while holding the subject alive")
                    if capture.down_pages():
                        raise CheckFailed(
                            f"a down page arrived for a subject that is checking in: "
                            f"{capture.down_pages()}"
                        )
                    time.sleep(PERIOD_SECONDS / 2)
                return "the check-in won the race; no page for a live subject"

            check("10. a ping during a failing page wins (rule 5)", ping_beats_a_failing_page)
        finally:
            faulty.stop()
            capture.heal()

        # ── 11. the statefile survives being killed mid-write ─────────────
        def statefile_is_replaced_not_rewritten() -> str:
            """The statefile must be swapped in by rename, never written in place.

            SPEC § Statefile mandates temp file → fsync → rename. Rename gives
            the path a *different inode* every time; an in-place write keeps the
            same one. That makes this a deterministic check rather than a race:
            killing the process mid-write would only catch a torn file by luck,
            since the window is well under a millisecond.
            """
            statefile.unlink(missing_ok=True)
            capture.clear()
            prober = boot()
            prober.start()
            try:
                inodes = []
                for _ in range(4):
                    if ping(prober) != 200:
                        raise CheckFailed("ping rejected")
                    wait_until(statefile.exists, timeout=5, what="the statefile to appear")
                    inodes.append(statefile.stat().st_ino)
                    time.sleep(0.3)

                if len(set(inodes)) == 1:
                    raise CheckFailed(
                        f"the statefile kept the same inode ({inodes[0]}) across "
                        f"{len(inodes)} writes — it is being rewritten in place, so a "
                        f"crash mid-write can leave a torn file. SPEC § Statefile "
                        f"requires temp file → fsync → rename."
                    )

                leftovers = [
                    p.name
                    for p in statefile.parent.iterdir()
                    if p.name != statefile.name
                ]
                if leftovers:
                    raise CheckFailed(f"temp files left beside the statefile: {leftovers}")
            finally:
                prober.stop()
            return f"{len(set(inodes))} distinct inodes across 4 writes — renamed, not rewritten"

        check("11. statefile is replaced atomically", statefile_is_replaced_not_rewritten)

        def statefile_survives_sigkill() -> str:
            """A supplementary crash test: kill -9 during in-flight writes.

            Weaker than check 11 and probabilistic — a non-atomic writer can pass
            this by luck — but it exercises the real crash path rather than
            inferring from inodes, and would catch a writer that renames into
            place while leaving the *content* incomplete.
            """
            statefile.unlink(missing_ok=True)
            capture.clear()
            kills = 8
            for attempt in range(kills):
                victim = boot()
                victim.start()

                # Hammer from a thread so the kill lands *during* a write rather
                # than after a burst has already finished — an idle process has
                # no torn write to catch.
                stop = threading.Event()

                def hammer(w: Watcher = victim, stop: threading.Event = stop) -> None:
                    while not stop.is_set():
                        with suppress(Exception):  # the process is about to die
                            ping(w)

                thread = threading.Thread(target=hammer, daemon=True)
                thread.start()
                try:
                    # Stagger, so the kills sample different points in the write.
                    time.sleep(0.4 + 0.13 * attempt)
                finally:
                    victim.kill()
                    stop.set()
                    thread.join(timeout=5)

                if not statefile.exists():
                    continue  # killed before the first write ever landed
                try:
                    state = read_state(statefile)
                except CheckFailed as e:
                    raise CheckFailed(f"torn statefile after kill #{attempt + 1}: {e}")
                if set(state) != {"last_seen", "alerted"}:
                    raise CheckFailed(
                        f"statefile has the wrong shape after kill #{attempt + 1}: {state}"
                    )
            return f"{kills} kills during in-flight writes, statefile intact every time"

        check("12. statefile survives SIGKILL mid-write", statefile_survives_sigkill)
    finally:
        watcher.stop()

    return results


# ──────────────────────────────── driver ──────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("impls", nargs="*", help="impl names (default: all discovered)")
    parser.add_argument("--list", action="store_true", help="list impls and exit")
    args = parser.parse_args()

    try:
        available = discover()
    except ValueError as e:
        eprint(f"bad manifest: {e}")
        return 2

    if args.list:
        for m in available:
            print(f"{m.name:12} {m.language:24} {' '.join(m.run)}")
        if not available:
            print("no implementations found (looked for */impl.json)")
        return 0

    selected = available
    if args.impls:
        by_name = {m.name: m for m in available}
        unknown = [n for n in args.impls if n not in by_name]
        if unknown:
            eprint(f"unknown impl(s): {unknown}. known: {sorted(by_name)}")
            return 2
        selected = [by_name[n] for n in args.impls]

    if not selected:
        eprint("no implementations found (looked for */impl.json)")
        eprint("add <lang>/impl.json declaring how to build and run it — see smoke/README.md")
        return 2

    capture_port = free_port()
    capture, server = start_capture_server(capture_port)
    failures: dict[str, list[Result]] = {}
    try:
        for manifest in selected:
            print(f"\n▶ {manifest.name} ({manifest.language})", flush=True)
            capture.clear()
            try:
                results = run_checks(manifest, capture, capture_port)
            except Exception as e:  # a harness-level fault, not a contract failure
                results = [Result("harness", False, f"{type(e).__name__}: {e}")]
                print(f"  ✗ harness error: {type(e).__name__}: {e}", flush=True)
            failed = [r for r in results if not r.passed]
            if failed:
                failures[manifest.name] = failed
    finally:
        server.shutdown()

    print()
    if failures:
        for name, failed in failures.items():
            print(f"✗ {name}: {len(failed)} check(s) failed")
            for r in failed:
                print(f"    {r.name}: {r.detail}")
        return 1
    print(f"✓ all implementations satisfy SPEC.md ({len(selected)} checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
