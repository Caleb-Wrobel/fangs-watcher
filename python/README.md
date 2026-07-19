# fangs-watcher — Python

An implementation of [`SPEC.md`](../SPEC.md). Starlette on uvicorn, httpx for the two outbound
calls, pydantic-settings for the environment.

## Run

```sh
./bootstrap.sh          # creates .venv, installs pinned deps

WATCHER_TOKEN=dev-token \
WATCHER_DISCORD_WEBHOOK=http://localhost:9099/hook \
WATCHER_SUBJECT=limen \
WATCHER_PERIOD_SECONDS=10 WATCHER_GRACE_SECONDS=20 \
.venv/bin/python -m watcher
```

Configuration is environment-only; see [`SPEC.md` § Configuration](../SPEC.md#configuration) for
every variable. Invalid config is refused at boot, naming the offending variables but never their
values.

## Test

```sh
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest              # 29 unit tests
.venv/bin/ruff check . && .venv/bin/ruff format --check .

../smoke/smoke.py python      # the shared contract suite
```

The unit tests cover what the end-to-end smoke test structurally cannot reach: a webhook that
fails to send, a ping that lands while a down page is mid-flight, and a crash between the
statefile's temp write and its rename.

## Layout

| File | What lives there |
|---|---|
| `watcher/config.py` | the `WATCHER_*` environment, typed and validated |
| `watcher/state.py` | the two persisted fields, and the atomic write |
| `watcher/core.py` | **the rules** — SPEC's rules 1–4, with no HTTP in sight |
| `watcher/app.py` | the two routes |
| `watcher/notify.py` | webhook POST, heartbeat GET, message wording |
| `watcher/__main__.py` | config load, logging, uvicorn |

`core.py` is deliberately free of Starlette so the contract can be read — and tested — without a
web framework in the way. It is the file to compare against the other languages.

## Notes on the implementation

**Secrets.** `WATCHER_TOKEN` and `WATCHER_DISCORD_WEBHOOK` are `SecretStr`, so a stray log line or
traceback renders `**********` rather than the credential. uvicorn's access log is disabled
outright — it would otherwise print the ping token in every request line, which is exactly the
leak `SPEC.md` forbids.

**A wrong method is a 404, not a 405.** Starlette answers a method mismatch with 405 by default,
which would confirm `/ping/{token}` exists whatever token you tried. An unknown path and a known
path with the wrong method are made indistinguishable.

**The token compare is constant-time** (`secrets.compare_digest`). It is a bearer credential; a
timing oracle would let it be guessed a character at a time.

**Notifications never block the pulse.** The recovery notice rides a Starlette `BackgroundTask`,
so a slow webhook cannot delay the `200` on a check-in. The state lock is likewise never held
across a network call.

**Failure is a retry, not a crash.** A down page marks `alerted` *before* sending so a slow
webhook cannot let the next tick page twice; if the send then fails, the mark is rolled back and
the next tick tries again — unless a ping arrived meanwhile, in which case the ping wins and the
rollback is skipped.

**Loopback by default.** `WATCHER_BIND` defaults to `127.0.0.1` because production runs this in a
`podman kube play` pod whose networking sidecar shares the namespace. See
[`SPEC.md` § Security](../SPEC.md#security-notes).
