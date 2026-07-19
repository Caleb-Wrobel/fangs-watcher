# fangs-watcher

An off-fleet **dead-man's switch** for the [fangs](https://github.com/) homelab: something checks in
on an interval, and the watcher pages when the check-ins stop (and again when they resume). It runs on
a small cloud VPS *outside* the fleet's failure domain, so it can report the gateway going dark when
nothing inside the fleet can.

Named for the fleet it serves, not the host it runs on.

## One contract, many tongues

This repo is, by design, **N implementations of one contract**. [`SPEC.md`](./SPEC.md) is the fixed
point; each language subdirectory is an interchangeable satisfier of it, kept deliberately **minimal
and idiomatic** so they read as clean comparison points — a small polyglot object lesson, and a
"complex hello-world" for kicking the tires on a new language.

| Impl | Status | Stack |
|---|---|---|
| [`scala/`](./scala) | in progress | Scala 3 · scala-cli · Cask · JDK scheduler |
| `go/` | planned | Go · stdlib |

The shared **smoke test** validates any implementation against `SPEC.md`.

## Run (Scala)

```sh
cd scala
# config via environment (see SPEC.md § Configuration)
WATCHER_TOKEN=dev-token \
WATCHER_DISCORD_WEBHOOK=http://localhost:9099/hook \
WATCHER_SUBJECT=limen \
WATCHER_PERIOD_SECONDS=10 WATCHER_GRACE_SECONDS=20 \
scala-cli run .
```

Secrets (`WATCHER_TOKEN`, `WATCHER_DISCORD_WEBHOOK`) are runtime environment only — never committed.
See `SPEC.md` for the full contract and the smoke-test definition.

## Deployment

The image is deployed by the [fangs](https://github.com/) config repo as a rootless Podman Quadlet on
the watcher host — this repo produces the image; fangs composes it. This repo knows nothing about the
host, TLS, or the fleet.
