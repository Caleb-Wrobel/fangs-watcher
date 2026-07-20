# fangs-watcher — the contract

This is the authoritative behavioral contract for the watcher. **Every implementation** in this repo
(`scala/`, `go/`, …) satisfies exactly this, and the shared **smoke test** verifies any of them
against it. The languages are interchangeable; this document is the fixed point.

## What it is

An off-fleet **dead-man's switch**. Something (in production: the fangs gateway `limen`) checks in on
an interval. The watcher pages when the check-ins **stop**, and pages again when they **resume**. It
persists just enough state to survive its own restart without lying.

## HTTP surface

| Method & path | Purpose | Response |
|---|---|---|
| `POST /ping/{token}` | Record a check-in. `{token}` must equal the configured secret. | `200` on match; **`404`** on mismatch (do not reveal the endpoint exists) |
| `GET /healthz` | The watcher's *own* liveness (for the host / a fronting proxy). | `200` while the process is up |

No other routes. No auth beyond the token-in-path (a capability URL; see Security).

**A wrong method is a `404`, not a `405`.** Many frameworks answer a path that matches with the
wrong method by returning `405 Method Not Allowed` — which confirms the route exists *whatever
token was tried*, defeating the `404`-on-mismatch rule above just as thoroughly as a `401` would.
An unknown path and a known path with the wrong method must be indistinguishable. (Concretely:
`GET /ping/{anything}` and `POST /healthz` both return `404`.)

## State

Two fields, and nothing else:

- `last_seen` — timestamp of the most recent accepted ping (or **absent** when never pinged).
- `alerted` — boolean; `true` while a "gone dark" page is outstanding.

### The rules (this is the feature — implement exactly)

1. **On an accepted ping:** set `last_seen = now`. If `alerted` was `true`, send a **recovery** notice
   and set `alerted = false`.
2. **On each ticker tick (every `period`):** if `last_seen` is present **and** `now - last_seen >
   period + grace` **and** `alerted` is `false` → send a **down** page and set `alerted = true`.
3. **Arm on first ping only.** If `last_seen` is **absent** (fresh install, no state), the watcher is
   *disarmed*: it sends nothing until the first ping arrives. A watcher that has never been pinged must
   never page.
4. **A restart during an outage must still page.** State is persisted (below), so on startup the
   watcher loads `last_seen`/`alerted` and the next tick evaluates rule 2 against them — a process that
   restarts while the subject is already dark will page on its first post-restart tick (unless it had
   already alerted, in which case it stays silent — no duplicate page).
5. **A notification that fails to send leaves `alerted` unchanged, and is retried while it still
   applies.** `alerted` means *a page was delivered*, not *a page was attempted* — persisting `true`
   for a page nobody received would silence the retry and lose the outage entirely. So:
   - A failed **down** page leaves `alerted = false`; the next tick re-evaluates rule 2 and tries
     again, with a freshly computed `{elapsed}`.
   - A failed **recovery** notice leaves `alerted = true`; the next accepted ping tries again.
   - "While it still applies" is load-bearing. An implementation may mark `alerted = true` *before*
     sending, to stop a slow webhook from letting the next tick page twice — but if the send then
     fails it must not roll that mark back over a ping that arrived meanwhile. A check-in during a
     failing page means the subject is demonstrably alive: the ping wins, the watcher ends disarmed,
     and the page is no longer applicable.

### Statefile

- JSON, human-readable (you will `cat` it during an incident): `{"last_seen": <iso8601|null>,
  "alerted": <bool>}`.
- **Atomic write on every change:** write to a temp file in the same directory → `fsync` → `rename`
  over the real path. A crash mid-write must leave the previous complete state, never a torn file.
- Path is configured (`WATCHER_STATE_FILE`). Absent file at startup = fresh/disarmed (rule 3).
- **A corrupt or unreadable statefile degrades to fresh — it must never stop the watcher booting.**
  Refusing to start would take the dead-man's switch down permanently over a file the watcher can
  simply rewrite on the next ping. A briefly disarmed watcher is recoverable; a watcher that is not
  running is not. Log a warning, start disarmed, re-arm on the next ping. (This is the one place the
  watcher may lose state: the alternative is losing the watcher.)

## Notifications

A notification is an HTTP `POST` of `{"content": "<message>"}` (Discord-webhook shape) to the
configured webhook URL. In tests the URL points at a local capture server — the watcher does not know
or care that it's Discord.

- **Down:** `🚨 {subject} is dark — no check-in for {elapsed}. Last check-in: {last_seen}.`
- **Recovery:** `✅ {subject} is back — check-in resumed at {now}.`

`{subject}` is configured (`WATCHER_SUBJECT`, e.g. `limen`). `{elapsed}` is human-readable
(e.g. `18m`). A failed notification POST is logged and retried on the next tick if still applicable;
it never crashes the watcher.

## healthchecks.io self-heartbeat (optional)

If `WATCHER_HEALTHCHECK_URL` is set, the watcher `GET`s it on each ticker tick — so the managed floor
(healthchecks.io) watches the watcher, and a dead watcher pages *there*. Unset → skipped. (Present in
the contract from day one; wired to a real check at the production cutover.)

## Configuration (environment only — 12-factor)

| Variable | Meaning | Default |
|---|---|---|
| `WATCHER_TOKEN` | the ping-path secret | *required* |
| `WATCHER_DISCORD_WEBHOOK` | notification POST target | *required* |
| `WATCHER_SUBJECT` | what's being watched, for messages | `the subject` |
| `WATCHER_PERIOD_SECONDS` | ticker interval / expected check-in cadence | `300` |
| `WATCHER_GRACE_SECONDS` | forgiveness beyond one period before paging | `900` |
| `WATCHER_STATE_FILE` | statefile path | `./watcher-state.json` |
| `WATCHER_BIND` | HTTP listen address | `127.0.0.1` |
| `WATCHER_PORT` | HTTP listen port | `8080` |
| `WATCHER_HEALTHCHECK_URL` | healthchecks.io self-heartbeat (optional) | *unset* |

No secret (`WATCHER_TOKEN`, `WATCHER_DISCORD_WEBHOOK`) ever appears in the repo, a commit, or a log line.

## Security notes

- The **token in the path is a bearer credential** — the unguessable URL *is* the auth. Threat model
  is false-alive spoofing (someone faking the subject's pulse), not data theft. A wrong token is a
  `404`, never a hint. Over TLS the path is encrypted in transit (TLS is added by a fronting proxy, not
  the watcher itself).
- The watcher makes only **outbound** notification calls and (optionally) the self-heartbeat; it needs
  no inbound reach to anything but its own `POST /ping`.
- **Bind loopback by default.** In production the watcher runs as one container in a `podman kube play`
  pod, sharing a network namespace with a **networking sidecar** that owns ingress (and TLS). The
  sidecar reaches the watcher over `127.0.0.1`, so binding all interfaces would publish `/ping` on the
  pod IP and bypass the only intended front door. An impl that ignores `WATCHER_BIND` and listens on
  `0.0.0.0` is **not** conformant — the default must fail closed. `WATCHER_BIND=0.0.0.0` remains
  available for deployments without a sidecar.
- `GET /healthz` is loopback-only under that default; this is fine, because the container runtime runs
  the liveness probe *inside* the pod's network namespace.

## The smoke test (what "passes the contract" means)

Boot the impl with a test config (throwaway token, `WATCHER_DISCORD_WEBHOOK` → a local capture server,
short period/grace), then assert, in order:

1. `GET /healthz` → `200`.
2. `POST /ping/{wrong}` → `404`; `POST /ping/{token}` → `200`, and the statefile now has a `last_seen`.
   `GET /ping/{token}` → `404` too, indistinguishable from an unknown path.
3. Withhold pings past `period + grace` → the capture server receives exactly one **down** message.
4. `POST /ping/{token}` again → the capture server receives one **recovery** message; `alerted` is now
   `false`.
5. (State) After a down page, restart the process → it loads state and does **not** re-page (already
   alerted); a fresh statefile-less boot pages nothing until first ping.
6. (Faults — rule 5) With the capture server rejecting webhook POSTs: a down page is **retried** and
   `alerted` stays `false` until one actually lands; a failed recovery leaves `alerted` `true` until a
   later ping delivers one. With the capture server *hanging* a POST before failing it, a ping that
   arrives mid-send wins — the watcher ends disarmed, keeps that check-in, and does not page a live
   subject.
7. (Statefile) The path's **inode changes on every write** — proof it was renamed into place rather
   than rewritten, and the only part of the atomic-write rule observable from outside the process.
   No temp files are left beside it in normal operation.

The harness deliberately does **not** verify `fsync` (a rename is observable, a flush is not) or the
exact wording of `{elapsed}`. Implementations should cover those, plus their own concurrency, in
their own test suites.
