# CentralIndexer — Resilience & Recovery Analysis

This document catalogues every meaningful failure mode for the CentralIndexer and the
recovery path (or open gap) for each. It is intended to drive future resilience work — not
to describe solutions that already exist.

**Companion documents:**
- [`scalability.md`](scalability.md) — concurrency limits and sizing constraints
- [`storage.md`](storage.md) — schema, JSONB structure, and index design
- [`Documentation/Operations/runbook.md`](../Operations/runbook.md) — immediate incident response

---

## System Context

The indexer is a single stateful process. Its authoritative state lives in PostgreSQL across
five tables (`repositories`, `branches`, `review_branches`, `reviews`, `reviews_index`). At
runtime it holds:

- An in-memory `PublisherRegistry` (per-repository `SubmissionPublisher`) for live SSE fan-out.
- A `ConnectionPool` of JDBC connections.
- One active provider plugin (GitHub / GitLab / Bitbucket) receiving webhook HTTP calls.

**There is no event log.** The indexer stores only the *latest* snapshot of each review.
Webhook events are the only mechanism that updates that snapshot. If an event is missed, the
snapshot remains stale until the next event for the same review arrives.

**Reconciliation cursor:** The `repositories` table carries a `kalynx_review_head` column —
the last-known head commit of the `kalynx_review` git notes branch for each repository. This
provides a durable, commit-addressed cursor into the authoritative review history. On startup
and after any recovery event, the indexer compares `kalynx_review_head` against the live
provider API, fetches any git notes commits in the gap via
`plugin.fetchNotesSince(owner, repo, fromCommit, toCommit)`, and replays them as idempotent
upserts across `reviews`, `branches`, `review_branches`, and `reviews_index`. The cursor is
updated atomically with the last review write it covers.

---

## Failure Scenarios

---

### 1. Server Crash and Restart (Unknown Duration)

**Trigger:** OOM kill, unhandled exception, OS reboot, deployment.

**Impact:**
- All open SSE connections are dropped immediately.
- Any webhook HTTP request being processed at the time of crash is lost without a response —
  the provider receives a connection error and will retry.
- In-memory `PublisherRegistry` state (which clients were connected, buffered events) is lost.
- The TCP port is unbound until the process restarts.

**Current behaviour:**
- The server starts fresh with no reconciliation step. It reads whatever is in PostgreSQL and
  begins accepting connections. If the DB is current, the snapshot served to reconnecting
  clients is correct. If the crash happened mid-write, the snapshot may be one event behind.
- `EventSource` clients auto-reconnect. Per the client connect sequence, each reconnecting
  client calls `GET /reviews` and `GET /branches` to re-sync state before opening the SSE
  stream. They will see whatever the DB holds at that moment.

**Gap:**
- Without reconciliation, events that arrived between the last committed DB write and the
  crash are permanently lost. The server has no way to know what it missed or for how long
  it was down.
- Without startup reconciliation, `reviews_index` may be silently stale for one or more
  reviews until the next webhook for those reviews arrives.
- Path D (startup reconciliation via `kalynx_review_head`) closes both gaps: the stored
  commit cursor identifies exactly what was missed, and the replay is idempotent.

**Recovery paths:**
- **A — Accept the gap (current posture):** For most deployments the gap is small (seconds).
  Reviews already at steady state are unaffected; only reviews that received an event during
  the exact crash window are stale.
- **B — Startup reconciliation via plugin:** On startup, call `plugin.reconcile(repo, since)`
  for every known repository, passing the most recent `last_updated` timestamp from
  `reviews_index`. The plugin fetches all events since that timestamp from the provider API
  and replays them. This closes the gap but adds startup latency proportional to the number
  of repositories.
- **C — Startup reconciliation via direct API fetch:** Instead of replaying events, fetch the
  current head commit for each branch from the provider API and compare against `branches`.
  Update any rows that differ. Faster than event replay and idempotent, but requires provider
  API access at startup.
- **D — Startup reconciliation via `kalynx_review_head` (chosen approach):** On startup, for
  each repository, compare `repositories.kalynx_review_head` against the current head commit
  of the `kalynx_review` branch returned by the provider API. If they differ, call
  `plugin.fetchNotesSince(owner, repo, storedHead, liveHead)` to retrieve the missed git
  notes commits and replay them as idempotent upserts across `reviews`, `branches`,
  `review_branches`, and `reviews_index`. Emit SSE events for each replayed review to any
  already-connected clients. Update `kalynx_review_head` atomically with the last write.
  This approach is commit-addressed rather than timestamp-based — precise, clock-independent,
  and covers server crash, OOM, and plugin-restart scenarios since all three result in a
  process restart triggering this path.

---

### 2. Git Provider Cannot Reach the Indexer (Network Partition, No Crash)

**Trigger:** Firewall change, routing outage, ingress IP change, DNS failure. The indexer is
running normally but the provider's webhook delivery is blocked.

**Impact:**
- All webhook events during the partition are silently queued by the provider and retried.
  GitHub retries for approximately 72 hours; GitLab and Bitbucket have similar retry windows.
- Connected SSE clients receive no events during the outage (correct — nothing has actually
  changed on the server side yet). They remain connected.
- `reviews_index` and `branches` tables freeze at their last-known state.

**Current behaviour:**
- Webhook retries arrive as a burst when connectivity is restored. Each is processed normally.
  Because `reviews_index` upserts gate on `last_updated`, retried events that are older than
  the current row are silently ignored — idempotent.
- If the partition lasted longer than the provider retry window, those events are permanently
  dropped. The provider may surface them in its webhook delivery log, but the indexer has no
  polling mechanism to detect or request a replay.

**Gap:**
- For extended outages (beyond the provider retry window), affected reviews are silently stale
  with no internal signal that anything is wrong.
- There is no mechanism to detect the start or end of a webhook blackout.

**Recovery paths:**
- **A — Automatic via provider retry (current posture):** Works for outages shorter than the
  provider retry window. Zero operator intervention needed.
- **B — Manual reconciliation:** After connectivity is restored, operator triggers
  `plugin.reconcile(repo, since)` for all repositories to pull missed events from the
  provider API. This is a planned capability of the `ProviderPlugin` SPI but is not
  automatically invoked.
- **C — Webhook delivery monitoring:** Track the most recent webhook delivery timestamp per
  repository. Alert when a repository has received no events for longer than the expected
  activity interval for that repository. This provides early detection of a blackout.
- **D — Reconciliation via `kalynx_review_head` on restart or schedule:** Because the
  `kalynx_review` branch is the authoritative persistent record of all reviews, any restart
  during or after a blackout triggers a reconciliation pass (see scenario 1, path D) that
  pulls all missed notes directly from the provider API, independent of the webhook delivery
  log. This removes the dependency on the provider retry window for recovering review state
  during extended outages.

---

### 3. PostgreSQL Is Unavailable

**Trigger:** DB node crash, network partition to PostgreSQL, maintenance window.

**Impact:**
- `GET /reviews` → 503.
- `GET /branches` → 503.
- `GET /health` → `{"status":"UP","db":"DOWN"}`.
- Incoming webhooks are processed by the plugin handler but the DB write fails with an
  exception. The handler returns HTTP 500 to the provider, which will retry.
- **Critically:** In-memory SSE fan-out still works. Connected clients receive live event
  signals even though the corresponding `reviews_index` write failed. This creates a
  split-brain: SSE says the review changed, but `GET /reviews` will return stale data until
  the DB recovers and the provider retries the webhook.

**Current behaviour:**
- No retry queue on the indexer side. Each failed webhook response triggers the provider to
  retry with exponential backoff. As long as PostgreSQL recovers within the provider retry
  window, the events will eventually persist.
- No circuit breaker. Every incoming webhook during the outage attempts a DB write and
  generates a 500.

**Gap:**
- The split-brain window: SSE clients see events that are not yet reflected in `GET /reviews`.
  A client that reconnects during a DB outage gets stale data from the last successful write,
  not the events it just saw over SSE.
- If the DB is down for longer than the provider retry window, those events are lost
  permanently even after DB recovery.
- No rate limiting on failed webhook responses. A large webhook burst during a DB outage
  could cause the provider to deprioritise delivery for this endpoint.

**Recovery paths:**
- **A — Provider retry (current posture):** Works if outage is short. The provider retries,
  DB recovers, events land correctly.
- **B — Local retry queue:** Buffer failed webhook payloads in memory (bounded queue).
  Process them once the DB connection is re-established. Trades memory for reduced dependency
  on provider retry timing. Queue is lost on crash — combine with provider retry for safety.
- **C — Persistent retry store:** Write failed payloads to a local file or secondary store
  (e.g. SQLite) and replay on recovery. Survives crash. Adds operational complexity.
- **D — Read replica for query endpoints:** Route `GET /reviews` and `GET /branches` to a
  PostgreSQL read replica. Queries continue during a primary failure. Writes still fail but
  read clients are unaffected.
- **E — Reconciliation via `kalynx_review_head` on DB recovery:** When the DB connection is
  re-established, trigger a reconciliation pass per repository: compare
  `repositories.kalynx_review_head` against the live provider head and replay any missed
  notes. This closes the gap between what the provider retried (which may have exceeded the
  retry window) and what actually landed in the DB, ensuring no review state is permanently
  lost even after an extended outage.

---

### 4. PostgreSQL Is Slow or Degraded (Not Down)

**Trigger:** Long-running query, missing index, lock contention, I/O saturation, underpowered
host.

**Impact:**
- DB connection pool threads back up. `db.pool_waiting_threads` rises.
- `GET /reviews` and `GET /branches` latencies climb.
- Webhook handlers queue behind pool acquisition, increasing response time.
- If pool is fully exhausted, HTTP requests return 503.
- SSE connections and in-memory event fan-out are unaffected.

**Current behaviour:**
- `MetricsCollector` exposes `db.pool_waiting_threads` and `db.get_reviews_p95_ms`.
  Both are visible at `GET /metrics`. See `runbook.md §DB Pool Exhaustion` for immediate
  mitigations.

**Gap:**
- No automatic pool scaling or circuit-breaking under sustained degradation.
- No query timeout is enforced on individual JDBC calls. A single long-running query can hold
  a connection indefinitely, starving other requests.

**Recovery paths:**
- **A — Increase pool size** (per runbook) and restart. Immediate relief.
- **B — JDBC statement timeout:** Set `socketTimeout` on the JDBC connection string.
  Enforce an upper bound on how long any single query can hold a pool connection.
- **C — Identify slow query:** Use `EXPLAIN ANALYZE` against `reviews_index` queries.
  Ensure `idx_reviews_index_last_updated` and `idx_reviews_index_repositories_gin` are being
  used. `pg_stat_activity` shows active long-running queries.

---

### 5. Provider Plugin Fails After Startup

**Trigger:** Unhandled exception in plugin event processing, plugin deadlock, network error
inside plugin code.

**Impact:**
- New webhooks may not be forwarded to `EventSinkImpl` if the plugin's internal routing
  breaks.
- `ReviewEvent` objects are never submitted to `PublisherRegistry` → SSE clients see no
  events.
- `reviews_index` receives no updates.
- HTTP webhook endpoint continues to return 200 (the `WebhookDispatcher` itself is still
  running; the exception may be caught inside the plugin).
- The failure may be silent — no crash, no log noise if the plugin swallows exceptions.

**Gap:**
- No watchdog on the plugin. A plugin that starts successfully and then silently stops
  processing events has no detection mechanism.
- Plugin exceptions that are caught internally cannot be distinguished from an absence of
  activity by normal periods of low webhook volume.

**Recovery paths:**
- **A — Restart:** Plugin is reloaded on restart. The startup reconciliation pass via
  `kalynx_review_head` (see scenario 1, path D) then catches any notes missed while the
  plugin was failing silently, without relying on provider retry.
- **B — Plugin health signal:** Add a `plugin.isHealthy()` or `getLastEventTimestamp()`
  method to the `ProviderPlugin` SPI. Expose via `GET /health`. An alerting system can then
  detect a plugin that has gone silent unexpectedly.
- **C — Webhook delivery rate monitoring:** Track the count of webhooks received per unit
  time. Alert on unexpected silence from a repository that normally generates steady activity.

---

### 6. Plugin Fails to Start

**Trigger:** Bad configuration, missing JAR, incompatible SPI version, provider API auth
failure at startup.

**Impact:**
- `Application.start()` throws before `IndexerHttpServer.start()` is called. The process
  exits. No HTTP port is bound.
- All clients get connection refused until the misconfiguration is fixed and the server
  restarted.

**Current behaviour:**
- The process fails fast with an exception in the log. No partial state is left in the DB.

**Gap:**
- No mechanism to serve `GET /health` (returning DOWN) while the plugin is misconfigured.
  External health checks cannot distinguish "server starting" from "server crashed".

**Recovery paths:**
- **A — Fix configuration and restart.** The DB retains its previous state; no data is lost.
- **B — Two-phase startup:** Bind the HTTP port and register `/health` first; start the
  plugin second. This allows health checks to observe the DOWN state rather than a connection
  refused.

---

### 7. SSE Client Loses Connection (Brief Network Hiccup)

**Trigger:** Client network instability, proxy timeout, OS TCP buffer limit, load balancer
idle-connection timeout.

**Impact:**
- The client's `EventSource` detects the dropped connection and reconnects automatically.
- Events that occurred during the disconnection are not replayed (no event log).
- The client may show stale UI state until it re-syncs.

**Current behaviour:**
- On reconnect the client calls `GET /reviews` and `GET /branches` to re-sync (per the
  connect sequence in `Client-Interface.md`). This gives the latest DB snapshot.
- Any events that arrived during the disconnection window will be absent from the snapshot
  only if they occurred after the last DB write visible to the reconnecting read — which is
  unlikely in practice (DB write is fast; the event is usually committed before reconnect).

**Gap:**
- A client that drops and reconnects in the exact same second as an event's DB write could
  theoretically see a snapshot that misses that event. The next event for the same review
  will correct it.
- Load balancers with aggressive idle timeouts will drop long-lived SSE connections.
  Clients must configure reconnect with jitter to avoid thundering herds.

**Recovery paths:**
- **A — State-based reconnect (current posture):** `GET /reviews` on reconnect provides the
  latest snapshot. Events missed during disconnection do not permanently affect state — the
  next incoming event will update the snapshot.
- **B — SSE keepalives:** Send periodic `: keepalive` comment lines to prevent load balancer
  idle timeouts. These are zero-cost for clients.

---

### 8. `reviews_index` Drifts from Normalised Tables

**Trigger:** Combination of failures — e.g., a webhook that updated `branches` succeeded but
the subsequent `reviews_index` upsert failed. Or a manual SQL edit to the normalised tables
without a corresponding `reviews_index` update.

**Impact:**
- `GET /reviews` returns stale or inconsistent `repositories` data for affected reviews.
- SSE clients receive correct signals (live events go through `PublisherRegistry`) but the
  snapshot at `GET /reviews` does not reflect them.
- The inconsistency is silent — no error is raised.

**Current behaviour:**
- The `BackfillBranchesTool` (`BackfillMain`) is designed exactly for this scenario: it
  re-derives `reviews_index.repositories` from the normalised tables via INNER JOIN. Running
  it closes the drift without requiring any event replay.

**Gap:**
- Drift detection is manual. There is no automated job that compares `reviews_index` against
  the normalised tables and alerts on discrepancy.
- The backfill tool must be run explicitly; it does not run automatically after failures.

**Recovery paths:**
- **A — Run backfill tool (current posture):**
  ```sh
  java -cp ReviewToolCentralIndexer.jar \
       com.kalynx.centralindexer.backfill.BackfillMain --dry-run  # inspect first
  java -cp ReviewToolCentralIndexer.jar \
       com.kalynx.centralindexer.backfill.BackfillMain            # apply
  ```
  Idempotent; safe to run at any time including during live traffic.
- **B — Scheduled consistency check:** Run the backfill tool in `--dry-run` mode nightly.
  Alert if `updatedReviews > 0` (i.e. drift is detected). Only run the full backfill if
  drift is confirmed.
- **C — Transactional webhook handling:** Wrap the normalised-table update and the
  `reviews_index` upsert in a single database transaction. Either both succeed or neither
  does. This prevents partial writes from creating drift in the first place.

---

### 9. Duplicate Webhook Delivery

**Trigger:** Provider retries a webhook that was actually delivered and processed (the
provider did not receive the 200 response due to a network glitch after the write committed).

**Impact:**
- The same event arrives twice. The second invocation attempts the same DB write.

**Current behaviour:**
- `reviews_index` upserts gate on `last_updated`: an incoming event only updates the row if
  its `last_updated` is >= the current value. A duplicate with the same timestamp produces an
  identical write — idempotent.
- `branches` and `repositories` upserts use `ON CONFLICT DO NOTHING` or equivalent — also
  idempotent.
- SSE: `PublisherRegistry.publish()` is called again and the duplicate event is delivered to
  connected clients. The client will observe two identical events and should be able to handle
  this (last-write-wins on the client side).

**Gap:**
- Duplicate SSE events delivered to connected clients. Clients must be tolerant of receiving
  the same `review_id` event more than once.

**Recovery paths:**
- **A — Client-side deduplication (current posture):** Clients that store state should
  ignore or merge duplicate events by `review_id` + `last_updated`.
- **B — Server-side deduplication by delivery ID:** Track the provider's `delivery_id` (e.g.
  `X-GitHub-Delivery`) in a short-lived cache (e.g. an in-memory LRU with TTL). Reject
  duplicate delivery IDs before they reach the DB or SSE fan-out. Adds memory usage; helpful
  if provider retry storms are frequent.

---

### 10. Out-of-Order Webhook Delivery

**Trigger:** Provider retries an older event after a newer event for the same review has
already been processed (common when retries arrive minutes later).

**Impact:**
- The older event's `last_updated` is earlier than the current row's `last_updated`.

**Current behaviour:**
- The `reviews_index` upsert condition prevents regression: an event with an older
  `last_updated` does not overwrite a more recent row. The older event is effectively dropped.
- The `branches` table is updated unconditionally on `head_commit` change — an older
  `head_commit` could potentially overwrite a newer one if the ordering protection is not
  present.

**Gap:**
- The `branches` table update path needs to be verified to also gate on a timestamp or
  sequence number, not just `head_commit`. If it does not, an out-of-order event could
  regress the `head_commit` for a branch.

**Recovery paths:**
- **A — Add `last_updated` gating to the `branches` upsert** (analogous to `reviews_index`).
  Reject a branch update whose `head_commit` corresponds to an older push event.
- **B — Accept occasional regression:** For most workloads the out-of-order window is small.
  A correct event for the same branch will follow shortly and correct the row.

---

### 11. TLS Certificate Expiry

**Trigger:** The keystore certificate used for HTTPS expires.

**Impact:**
- All HTTPS connections are rejected by clients. All endpoints are unreachable.
- The process itself continues running but is effectively inaccessible.

**Current behaviour:**
- TLS is optional (`server.tls.enabled`). When enabled, the keystore is loaded at startup
  from the path specified in config. There is no runtime certificate rotation — a restart is
  required to load a new certificate.

**Gap:**
- No built-in certificate expiry monitoring or alerting.
- Rotation requires a restart (and therefore a brief reconnect storm).

**Recovery paths:**
- **A — Replace keystore and restart** (current posture). Follow the restart procedure in
  `runbook.md` to minimise the reconnect burst.
- **B — External certificate monitoring:** Alert when the certificate expiry is within N days.
  Automate rotation (e.g. Let's Encrypt / ACME) before expiry so a restart is planned rather
  than reactive.
- **C — Terminate TLS at a load balancer or reverse proxy** (nginx, Caddy, HAProxy). The
  indexer then serves plain HTTP internally. TLS rotation is handled by the proxy without
  touching the indexer.

---

### 12. Memory Exhaustion / OOM Kill

**Trigger:** Too many concurrent SSE clients (~10 KB each), a large event buffer in
`SubmissionPublisher`, or a memory leak.

**Impact:**
- JVM is killed by the OS OOM killer.
- Same consequences as a server crash (scenario 1).
- Restart may immediately OOM again if the root cause is not addressed.

**Current behaviour:**
- `sse.connected_clients_total` is visible at `GET /metrics`. The threshold for concern is
  ~40,000 clients (see `scalability.md`).
- `SubmissionPublisher` drops slow subscribers rather than buffering indefinitely, capping
  per-subscriber memory.

**Gap:**
- No hard limit on the number of concurrent SSE connections. A sustained connection storm
  can exhaust heap before the operator notices.
- No JVM heap metric exposed via `GET /metrics`.

**Recovery paths:**
- **A — Restart and add a second node** before the threshold is reached again. The startup
  reconciliation pass via `kalynx_review_head` (see scenario 1, path D) closes the event gap
  caused by the OOM, so restarting is sufficient to restore review state consistency.
- **B — Add a `maxConnectedClients` config gate:** Reject new SSE connections (503) once the
  limit is reached. This caps memory use and gives operators a clear signal rather than an
  unpredictable OOM.
- **C — Expose JVM heap usage** (`Runtime.getRuntime().totalMemory()`) in `GET /metrics` so
  it can be monitored alongside `sse.connected_clients_total`.

---

### 13. Full Client Reconnect Storm After Restart

**Trigger:** Any planned or unplanned restart. All clients reconnect within their jitter
window (typically 0–30 seconds by default).

**Impact:**
- Every reconnecting client calls `GET /reviews`. With default client reconnect behaviour
  (no jitter), all requests arrive simultaneously.
- DB pool saturation (see scenario 4). `db.pool_waiting_threads` spikes.
- `GET /reviews` p95 latency may exceed 500 ms during the burst.

**Current behaviour:**
- `db.get_reviews_p95_ms` is visible in `GET /metrics`. Per `runbook.md`, alert threshold
  is 500 ms.

**Gap:**
- The server cannot enforce client reconnect jitter — it is a client-side responsibility.
- With many clients using default `EventSource` settings (immediate reconnect), the burst is
  severe at scale.

**Recovery paths:**
- **A — Client-side jitter (strongly recommended):** Configure SSE clients to reconnect with
  a random delay of 2–10 seconds. This flattens the burst across the reconnect window.
- **B — Temporary pool size increase before restart:** Pre-emptively increase
  `database.poolSize` before a planned restart and restore it after the reconnect burst
  subsides.
- **C — Staged restart with two nodes:** With two nodes behind a load balancer, restart one
  at a time. Only half the clients reconnect at once, halving the burst.
- **D — `GET /reviews` response caching:** Cache the response at the application level with a
  short TTL (e.g. 5 seconds). Reconnect bursts collapse into a single DB read. Acceptable
  because `reviews_index` is event-driven, not polled.

---

### 14. Backfill Run During Peak Load

**Trigger:** Operator runs `BackfillMain` during business hours or a reconnect storm.

**Impact:**
- Backfill performs a full sequential scan of `reviews_index` and writes updated JSONB rows.
  Under a busy workload this competes with live reads from `GET /reviews`.
- DB pool connections are occupied by the backfill batches, reducing availability for
  request-handling.

**Current behaviour:**
- Backfill supports `--batch-size N` to throttle the number of reviews processed per round.
  There is no built-in delay between batches.

**Recovery paths:**
- **A — Run during low-traffic windows** (e.g. nightly workflow, off-hours).
- **B — Add inter-batch delay** to `BackfillBranchesTool` (e.g. 100 ms sleep between batches)
  to yield DB pool connections to live requests.
- **C — Dedicated DB connection for backfill** outside the shared `ConnectionPool`. This
  prevents backfill from competing for live-traffic connections.

---

## Gap Summary

| Scenario | Current posture | Biggest gap | Priority path |
|---|---|---|---|
| Server crash | State from DB on restart; no reconciliation | Missed events during crash window | Startup reconciliation via `kalynx_review_head` (path D) |
| Webhook blackout | Provider retry covers short outages | Extended outages permanently lose events | Reconciliation via `kalynx_review_head` on restart/schedule (path D) |
| PostgreSQL down | Provider retry; SSE fan-out continues | DB-SSE split-brain; retry window expiry | Reconciliation via `kalynx_review_head` on DB recovery (path E) |
| PostgreSQL slow | Pool size tuning per runbook | No JDBC query timeout | Statement timeout on connections |
| Plugin failure | Restart + `kalynx_review_head` reconciliation | Silent failure post-start | Plugin health signal in `GET /health` |
| `reviews_index` drift | `BackfillMain` fixes on demand | No automatic drift detection | Nightly dry-run check (path B) |
| OOM | Restart + `kalynx_review_head` reconciliation | No connection count ceiling | `maxConnectedClients` gate (path B) |
| Reconnect storm | Client jitter (advisory) | No server-side enforcement | Response caching (path D) |
| Duplicate webhooks | DB upsert idempotency | Duplicate SSE events to clients | Client-side dedup (path A) |
| Out-of-order events | `reviews_index` gating | `branches` table may lack gating | Add timestamp gate to branches upsert |
| TLS expiry | Restart with new cert | No expiry monitoring | External cert monitoring or proxy TLS |
| Backfill under load | `--batch-size` throttle | No inter-batch yield | Backfill-dedicated connection + delay |

---

## Current Resilience Strengths

- **Stateless hot path:** SSE fan-out is in-memory. A DB slowdown does not affect live event
  delivery to connected clients.
- **Idempotent writes:** `reviews_index`, `branches`, and `repositories` upserts are all safe
  to retry and run multiple times.
- **`last_updated` gating:** Older events cannot regress `reviews_index` to an earlier state.
- **State-based reconnect:** Clients re-sync from `GET /reviews` on reconnect — no event
  replay or cursor tracking needed.
- **Backfill tool:** `BackfillMain` provides a safe, idempotent path to re-derive
  `reviews_index` from the normalised tables without replaying events.
- **Per-repository publishers:** Only clients watching the affected repository are woken per
  event — SSE fan-out does not degrade with total client count.
- **`kalynx_review_head` reconciliation cursor:** The stored head commit of the
  `kalynx_review` branch acts as a durable, clock-independent cursor. Any restart (crash,
  OOM, plugin failure) triggers a reconciliation pass that closes the event gap precisely —
  without relying on webhook retry windows, timestamp approximations, or a separate event log.
