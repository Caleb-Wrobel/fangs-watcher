# fangs-watcher — Go

An implementation of [`SPEC.md`](../SPEC.md). Go on the standard library only: `net/http` for the
two routes and the two outbound calls, a `time.Timer` loop for the ticker, `encoding/json` for the
statefile. No third-party modules — there is no `go.sum` because there is nothing to sum.

> **Status: complete.** `../smoke/smoke.py go` passes all 17 checks — the impl satisfies the
> contract end to end — and the unit suite runs clean under `-race`.

## Run

```sh
go run ./cmd/watcher
```

with the environment set:

```sh
WATCHER_TOKEN=dev-token \
WATCHER_DISCORD_WEBHOOK=http://localhost:9099/hook \
WATCHER_SUBJECT=limen \
WATCHER_PERIOD_SECONDS=10 WATCHER_GRACE_SECONDS=20 \
go run ./cmd/watcher
```

Requires Go 1.26+ (the version in `go.mod`). Configuration is environment-only; see
[`SPEC.md` § Configuration](../SPEC.md#configuration) for every variable.

## Test

```sh
go test -race ./...        # the unit suite
../smoke/smoke.py go       # the shared contract suite
```

The unit suite covers what the end-to-end smoke test structurally cannot reach: a webhook that
fails to send, a ping that lands while a down page is mid-flight, and the rollback that makes a
failed page retry on the next tick.

## Layout

| File | What lives there |
|---|---|
| `go.mod` | module path and the Go version |
| `internal/watcher/config.go` | the `WATCHER_*` environment, typed and validated |
| `internal/watcher/state.go` | the two persisted fields, and the atomic write |
| `internal/watcher/watcher.go` | **the rules** — SPEC's rules 1–5, with no HTTP in sight |
| `internal/watcher/http.go` | the two routes |
| `internal/watcher/notify.go` | webhook POST, heartbeat GET, message wording |
| `cmd/watcher/main.go` | config load, the ticker, the server |
| `Containerfile` | the published image (two stages, two arches) |
| `impl.json` | how the shared smoke harness builds and runs this impl |

`watcher.go` is deliberately free of `net/http` so the contract can be read — and tested — without
a web framework in the way. It is the file to compare against
[`python/watcher/core.py`](../python/watcher/core.py) and
[`scala/.../Watcher.scala`](../scala/src/main/scala/watcher/Watcher.scala).

## Install

The image is published to GHCR as a **two-architecture manifest** — `linux/amd64` and
`linux/arm64` under one tag — so the same reference works whichever way the host is running:

```sh
podman pull ghcr.io/caleb-wrobel/fangs-watcher/watcher-go:0.1.0
```

Podman resolves the arch for you. To confirm what you got, or to force one:

```sh
podman image inspect ghcr.io/caleb-wrobel/fangs-watcher/watcher-go:0.1.0 --format '{{.Architecture}}'
podman pull --arch arm64 ghcr.io/caleb-wrobel/fangs-watcher/watcher-go:0.1.0
```

**Pin an immutable tag** — a specific `:X.Y.Z`, or a `@sha256:` digest — never `:edge` or
`:latest`. See [`PUBLISHING.md`](../PUBLISHING.md) for what each tag means and when it moves.

Build it yourself instead, if you'd rather:

```sh
podman build -t watcher-go:dev go                                        # your host's arch
podman build --platform linux/amd64,linux/arm64 --manifest watcher-go:dev go   # both
```

## Operate

### Run it

```sh
podman run -d --name watcher \
  -e WATCHER_TOKEN=... \
  -e WATCHER_DISCORD_WEBHOOK=https://discord.com/api/webhooks/... \
  -e WATCHER_SUBJECT=limen \
  -e WATCHER_PERIOD_SECONDS=300 \
  -e WATCHER_GRACE_SECONDS=900 \
  -e WATCHER_STATE_FILE=/var/lib/watcher/watcher-state.json \
  -v watcher-state:/var/lib/watcher \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/caleb-wrobel/fangs-watcher/watcher-go:0.1.0
```

The container runs as **uid 65532** (distroless `nonroot`) with a working directory of
`/var/lib/watcher`. A bind-mounted statefile directory must be chowned to that uid. Note it differs
from the Scala image's 10001 — chown to the uid of the image you actually pinned.

`WATCHER_BIND` defaults to `127.0.0.1` — inside a container, *the container's own* loopback. That
is the production posture: the watcher shares a pod namespace with the sidecar that fronts it. To
reach it across a port mapping instead, set `WATCHER_BIND=0.0.0.0`, which publishes `/ping` on every
interface the container has.

### The knobs

| Variable | Required | Default | Notes |
|---|---|---|---|
| `WATCHER_TOKEN` | ✅ | — | the bearer credential in the ping URL |
| `WATCHER_DISCORD_WEBHOOK` | ✅ | — | a secret; never logged, never baked into the image |
| `WATCHER_SUBJECT` | | `the subject` | what the pages call the thing being watched |
| `WATCHER_PERIOD_SECONDS` | | `300` | tick cadence; must be a positive integer |
| `WATCHER_GRACE_SECONDS` | | `900` | slack on top of the period before dark |
| `WATCHER_STATE_FILE` | | `./watcher-state.json` | relative paths resolve against the working dir |
| `WATCHER_BIND` | | `127.0.0.1` | see above |
| `WATCHER_PORT` | | `8080` | 1–65535 |
| `WATCHER_HEALTHCHECK_URL` | | *(unset)* | healthchecks.io heartbeat; unset disables it |

A watcher is **dark** after `PERIOD + GRACE` seconds of silence — 20 minutes on the defaults.

Misconfiguration is a **refusal to boot, exit code 2**, with every problem reported at once rather
than the first. The messages name the offending variable and never its value, because two of them
are secrets. A container that exits immediately with code 2 is a config error, not a crash:

```sh
podman logs watcher
```

### Check on it

```sh
curl -sf http://127.0.0.1:8080/healthz                     # the watcher's own liveness -> 200
curl -X POST http://127.0.0.1:8080/ping/$WATCHER_TOKEN     # a check-in -> 200
podman exec watcher cat /var/lib/watcher/watcher-state.json
```

The statefile is the whole persisted state, and it is meant to be read by eye at 3am:

```json
{
  "last_seen": "2026-08-18T15:31:55Z",
  "alerted": false
}
```

`alerted` is `true` only when a down page has actually been delivered and no check-in has arrived
since. `last_seen` absent (`null`) means never pinged — and a never-pinged watcher is **disarmed**
and will never page, which is the safe direction to fail on a fresh install.

Anything unexpected — a wrong token, a wrong method, an unknown path — is a byte-identical `404`.
There is no response that distinguishes them, deliberately: a `401` or a `405` would confirm the
endpoint exists whatever token was tried.

### Upgrade and roll back

Both are a re-pin, because state lives in the volume rather than the image:

```sh
podman rm -f watcher
podman run -d ... ghcr.io/caleb-wrobel/fangs-watcher/watcher-go:0.2.0   # same -v
```

Restarting mid-outage does **not** re-page: `alerted` survives in the statefile (SPEC rule 4).
Downgrading is the same command with an older tag — and since the statefile format is the
contract's, the same volume rolls back to the Python or Scala image just as well.

## Notes on the design

**The ticker is fixed *delay*, not fixed *rate*.** `time.Ticker` keeps its own schedule and buffers
a missed tick, so a webhook that blocks a tick lets it deliver a backlogged tick the instant the
send returns — which can page for an outage a check-in already ended. `main.go` resets a
`time.Timer` *after* each tick, matching Python's `sleep(period)` and Scala's
`scheduleWithFixedDelay`. A late tick is still correct, since the rule counts wall-clock since
`last_seen`. This is the one bug the smoke test caught and the unit suite did not.

**Where the concurrency is.** `net/http` serves each request on its own goroutine while the ticker
ticks on another, so `RecordPing` and `OnTick` genuinely race. The mutex covers in-memory mutation
and the statefile write, never a network call — a wedged webhook must not block a ping.

**`alerted` is marked before the send, rolled back if it fails.** Marking first stops a slow webhook
from letting the next tick page twice; the rollback is what makes rule 5's retry work, since
`alerted` must mean *delivered*, not *attempted*. Both are conditional on nothing having moved
underneath them.

**A wrong method is a 404, not a 405.** Go 1.22's method-matching patterns (`POST /ping/{token}`)
answer a method mismatch with 405, which SPEC forbids — it confirms the route exists whatever token
was tried. `http.go` matches on path only and checks the method by hand.

**Static binary, distroless image.** `CGO_ENABLED=0` removes the libc dependency, which is what
lets the runtime stage be distroless and what makes cross-compiling free — the build runs on the
builder's arch and emits an arm64 binary without emulation. That is why this impl ships two arches
and the JVM one does not.
