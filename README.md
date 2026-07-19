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
| [`python/`](./python) | ✅ passes the contract | Python 3 · Starlette · uvicorn · httpx · pydantic-settings |
| `scala/` | planned | Scala 3 · scala-cli · Cask · JDK scheduler |
| `go/` | planned | Go · stdlib |

The shared [**smoke test**](./smoke) validates any implementation against `SPEC.md`:

```sh
./smoke/smoke.py            # every impl
./smoke/smoke.py python     # one of them
```

Each impl declares how to build and run itself in its own `impl.json`, so the harness never learns
about a language — adding one is adding a directory. See [`smoke/README.md`](./smoke/README.md).

## Run

```sh
cd python && ./bootstrap.sh
# config via environment (see SPEC.md § Configuration)
WATCHER_TOKEN=dev-token \
WATCHER_DISCORD_WEBHOOK=http://localhost:9099/hook \
WATCHER_SUBJECT=limen \
WATCHER_PERIOD_SECONDS=10 WATCHER_GRACE_SECONDS=20 \
.venv/bin/python -m watcher
```

Secrets (`WATCHER_TOKEN`, `WATCHER_DISCORD_WEBHOOK`) are runtime environment only — never committed.
See `SPEC.md` for the full contract and the smoke-test definition.

## Deployment

The image is deployed by the [fangs](https://github.com/) config repo as a rootless Podman Quadlet on
the watcher host — this repo produces the image; fangs composes it. This repo knows nothing about the
host, TLS, or the fleet.

In production the watcher runs as one container in a `podman kube play` pod, sharing a network
namespace with a **networking sidecar** that owns ingress and TLS. The sidecar reaches the watcher
over loopback, which is why `WATCHER_BIND` defaults to `127.0.0.1` — binding all interfaces would
publish `/ping` on the pod IP and bypass the intended front door. The smoke test enforces this.
