# The shared smoke test

One harness, one contract, N implementations. This checks any impl in the repo against
[`SPEC.md`](../SPEC.md) — it is the executable definition of "passes the contract".

Standard library Python only (3.10+). No install step, no dependencies.

```sh
./smoke/smoke.py           # every impl that has a manifest
./smoke/smoke.py scala     # just one
./smoke/smoke.py --list    # what's discoverable
```

Exit status is `0` when every selected impl conforms, `1` on a contract failure, `2` on a
usage or manifest error.

## Adding a language

The harness knows nothing about any language. It globs `*/impl.json`, so a new impl is a new
directory — the harness is never edited:

```json
{
  "language": "Scala 3 · scala-cli · Cask",
  "build": ["scala-cli", "compile", "."],
  "run": ["scala-cli", "run", ".", "--"],
  "ready_timeout_seconds": 120
}
```

| Field | Meaning |
|---|---|
| `language` | shown in output; how you'd describe the stack |
| `run` | **required** argv to start the watcher, run with the impl dir as cwd |
| `build` | optional argv run once before starting (compile, `go build`, …) |
| `name` | defaults to the directory name |
| `ready_timeout_seconds` | how long `/healthz` may take to come up (default `90`; a cold JVM needs more) |

The harness supplies all configuration through the environment per `SPEC.md`, including
`WATCHER_PORT` and `WATCHER_BIND` — an impl must take its port and bind address from there,
never hardcode them, since each boot gets a fresh port.

## What it asserts

Beyond the five numbered steps in `SPEC.md`, the harness checks the things that are easy to
get wrong and invisible in manual testing:

- **Silence is tested, not assumed.** A watcher that pages on every tick, or one that pages
  before it has ever been pinged, fails — absence of a page is asserted with a real wait, not
  inferred from a passing "it paged once".
- **The bind default fails closed.** With `WATCHER_BIND=127.0.0.1` the harness confirms the
  port is *not* reachable on the host's routable address. Production runs the watcher in a
  `podman kube play` pod behind a networking sidecar that reaches it over loopback; listening
  on all interfaces would publish `/ping` on the pod IP. Skipped on loopback-only hosts.
- **A wrong method is a 404, not a 405.** A 405 on `GET /ping/<token>` confirms the route exists
  whatever token was tried, which defeats the 404-on-mismatch rule. Most frameworks do this by
  default, so it is checked rather than trusted.
- **Restart behaviour**, by actually killing the process mid-outage and booting it again
  against the same statefile (SPEC rule 4), and by booting with no statefile at all (rule 3).

## What it does *not* assert

Two clauses of the contract are left to each impl's own tests, because forcing them from outside
the process is impractical:

- **Rule 5's retry semantics** — a notification that fails mid-page, and a ping that races a send.
  The harness cannot make the capture server fail on cue without becoming a mock.
- **The atomic statefile write** — a crash between the temp write and the rename.

`python/tests/` covers both. An impl that skips them can still pass this suite, so don't read a
green run as proof they hold.
- **Process groups are killed, not just the launcher** — a launcher like `scala-cli` spawns a
  JVM child that would otherwise survive and hold the port.

Timings are deliberately tiny (`period` 2s, `grace` 2s), so a full run is a couple of minutes
rather than the production half-hour.
