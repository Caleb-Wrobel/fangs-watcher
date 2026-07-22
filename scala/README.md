# fangs-watcher — Scala

An implementation of [`SPEC.md`](../SPEC.md). Scala 3 on scala-cli, Cask for the two routes,
requests-scala for the two outbound calls, a JDK `ScheduledExecutorService` for the ticker.

> **Status: scaffold.** Every method body is `???` and every test is `.ignore`d. It compiles and the
> suite is green (57 ignored, 0 run), but it does not yet satisfy the contract — `../smoke/smoke.py
> scala` fails, by design, until the guts are filled in.

## Run

```sh
scala-cli compile .

WATCHER_TOKEN=dev-token \
WATCHER_DISCORD_WEBHOOK=http://localhost:9099/hook \
WATCHER_SUBJECT=limen \
WATCHER_PERIOD_SECONDS=10 WATCHER_GRACE_SECONDS=20 \
scala-cli run .
```

Requires a JDK 21+ on `PATH`. Configuration is environment-only; see
[`SPEC.md` § Configuration](../SPEC.md#configuration) for every variable.

## Test

```sh
scala-cli test .              # the unit suite
../smoke/smoke.py scala       # the shared contract suite
```

Tests are `.ignore`d while their subject is a stub — drop the `.ignore` as you fill each one in, so
the ignored count is an honest to-do list. The suite covers what the end-to-end smoke test
structurally cannot reach: a webhook that fails to send, a ping that lands while a down page is
mid-flight, and the wording of `{elapsed}`.

## Layout

| File | What lives there |
|---|---|
| `project.scala` | scala version, dependencies, compiler options |
| `src/main/scala/watcher/Config.scala` | the `WATCHER_*` environment, typed and validated |
| `src/main/scala/watcher/State.scala` | the two persisted fields, and the atomic write |
| `src/main/scala/watcher/Watcher.scala` | **the rules** — SPEC's rules 1–5, with no HTTP in sight |
| `src/main/scala/watcher/Http.scala` | the two routes |
| `src/main/scala/watcher/Notifier.scala` | webhook POST, heartbeat GET, message wording |
| `src/main/scala/watcher/Main.scala` | config load, the scheduler, the server |

`Watcher.scala` is deliberately free of Cask so the contract can be read — and tested — without a
web framework in the way. It is the file to compare against
[`python/watcher/core.py`](../python/watcher/core.py).

## Notes on the design

**No effect system.** The contract is small and synchronous: two routes, one timer, three outbound
calls. Cats Effect or ZIO would be a larger idea than the problem, and this repo is meant to read as
a comparison point rather than a showcase. Blocking calls on Undertow's worker threads are the
honest shape here.

**The scheduler is `java.util.concurrent`.** SPEC rule 2 is "do this every period", and the JDK
already says that. `scheduleAtFixedRate` is the right variant: the contract counts elapsed
wall-clock since `last_seen`, so a late tick is still correct, and fixed-rate keeps the cadence from
drifting over days of uptime. The tick must never throw — a `ScheduledExecutorService` silently
stops rescheduling a task that threw, which would strand the watcher answering `/healthz` while
never paging again.

**Where the concurrency actually is.** Cask serves each request on an Undertow worker thread while
the scheduler ticks on its own, so `recordPing` and `onTick` genuinely race. Hold the monitor across
in-memory mutation and the statefile write, and never across a network call — a wedged webhook must
not block a ping. This is the same discipline as the Python impl's lock, minus the event loop.

**A wrong method is a 404, not a 405.** Cask answers a method mismatch with 405 by default, which
would confirm `/ping/{token}` exists whatever token you tried. Both `handleNotFound` and
`handleMethodNotAllowed` are overridden to the same response, byte for byte.

**No JVM pin.** `project.scala` deliberately omits `//> using jvm`, which would have scala-cli fetch
its own JDK — a large first-build download, and an outright failure on a host without reach to the
Adoptium releases. The JDK on `PATH` is used as-is.
