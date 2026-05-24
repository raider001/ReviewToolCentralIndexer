# CentralIndexer — Single-Node Scalability

This document captures honest expectations and theoretical limits for a single
CentralIndexer node. It drives the decision to keep the deployment simple and
identifies the real triggers that would justify a more complex architecture.

---

## Target Workload

| Dimension | Target | Basis |
|---|---|---|
| Concurrent SSE clients | ~10,000 | Virtual threads, one per client |
| Repositories served | ~10,000s | One `LISTEN` channel covers all |
| Webhook ingestion rate | < 100 events/second peak | 10,000 repos × low-frequency human activity; spikes during business hours |
| Retention window (optional event archive) | N/A by default | The indexer stores only latest snapshots; an archive is optional and sized separately |

---

## Theoretical Limits by Resource

### Memory — first real ceiling

Each SSE client occupies:
- A virtual thread stack: ~1 KB initial, grows on deep call stacks
- A `SubmissionPublisher` subscriber buffer: 8 events × ~500 bytes ≈ 4 KB (bounded; slow clients are dropped, not buffered indefinitely)
- An OS TCP socket: ~4–8 KB kernel buffer

**Rough estimate: ~10 KB per client end-to-end.**

| Client count | Estimated heap pressure |
|---|---|
| 1,000 | ~10 MB — negligible |
| 10,000 | ~100 MB — comfortable on any cloud instance |
| 50,000 | ~500 MB — approaches a 1 GB heap limit |
| 100,000 | ~1 GB — requires a well-tuned 2–4 GB heap |

A standard small cloud instance (2 vCPU, 4 GB RAM, 2 GB heap) handles 10,000 clients
with significant headroom. **Memory becomes the first constraint above ~40,000 clients.**

---

### PostgreSQL connection pool — reconnect/read thundering herd

The pool holds a bounded number of JDBC connections shared across virtual threads. Under
normal operation this is not a bottleneck. With the current client connect flow, clients
fetch a snapshot via `GET /reviews` (a read against the small `reviews_index`) and then
open the SSE stream. If many clients reconnect at once this produces a read thundering
herd against `reviews_index` rather than many per-repo replay `SELECT`s.

Mitigations: increase the pool size temporarily for restart windows, add jittered client
reconnect delays, prioritise establishing live streams before full reconcilation, and
consider a cached/read-replica for heavy reconnect bursts.

---

### Read index sizing

The indexer maintains a compact denormalized `reviews_index` (one row per active review)
to serve `GET /reviews` with low latency. The system no longer relies on an append-only
event table for normal operation; if you choose to run a separate event archive for
auditing/backfill, size that component independently.

Capacity planning should focus on the rate of upserts to `reviews_index` (writes/sec)
and the size of each row (small JSON describing repositories/branches). Partitioning and
per-repository strategies are no longer primary concerns for the read index.

---

### `reconcile()` startup time at 10,000 repositories

On startup the core calls `reconcile(repository, since)` once per repository present in `repository_state`.
Each call makes at least one HTTP request to the provider API. With 10,000 repositories:

| Concurrency | Time per call | Total time |
|---|---|---|
| 1 (serial) | 150 ms | ~25 minutes — impractical |
| 50 (bounded parallel) | 150 ms | ~30 seconds — acceptable |
| 200 (bounded parallel) | 150 ms | ~8 seconds — risks provider rate limits |

**Required approach:** Reconcile calls must be parallelised with bounded concurrency
(a virtual thread semaphore or fixed-size executor). The bound should be configurable
and default to ~20–50 to stay within standard provider API rate limits.

Additionally, repositories with no stored events older than the retention window can
skip reconciliation — any backfilled events would be pruned immediately on arrival.

---

### `SubmissionPublisher` fan-out efficiency at 10,000 repositories

A naive single global `SubmissionPublisher` would deliver every event to every connected
SSE client regardless of which repository they are watching. At 10,000 repositories and
10,000 clients, every event would trigger 10,000 wakeups with a 0.01% useful hit rate:

| Event rate | Clients | Wakeups/sec | Useful wakeups/sec |
|---|---|---|---|
| 10/sec | 10,000 | 100,000 | 10 |
| 100/sec | 10,000 | 1,000,000 | 100 |

**This is why the design uses a per-repository `SubmissionPublisher` map**
(`ConcurrentHashMap<String, SubmissionPublisher<ReviewEvent>>`). Only clients watching
the relevant repository are woken per event. At 100 events/second across 10,000
repositories, the average client is woken once per second, not 100 times.

---

### SSE write throughput — high event rate × high client count

Writing one SSE frame to N clients = N kernel `write()` syscalls. With a per-repository
publisher, N is only the number of clients watching that specific repository, not the
total client count.

| Event rate | Clients per repo | Syscalls/sec | Assessment |
|---|---|---|---|
| 100/sec | 1 | 100 | Trivial |
| 100/sec | 50 | 5,000 | Fine |
| 100/sec | 10,000 (hot repo) | 1,000,000 | CPU measurable (~1–2 cores) |

**SSE write throughput is not a practical limit unless a single repository has
thousands of concurrent watchers at high event frequency simultaneously.**

---

### PostgreSQL `pg_notify` throughput

`pg_notify` is synchronous within a transaction. At very high insert rates the
notification channel can lag the write path.

- Observed limit: **~1,000 notifications/second** before delivery latency grows noticeably.
- At the revised peak estimate of 100 webhooks/second, there is 10× headroom.

**`pg_notify` throughput is not a practical limit for this use case.**

---

### `pg_notify` payload considerations

Notifications carry small signals (e.g. `review_id`, `event_type`, routing keys and
occasionally minimal routing fields such as `repository_url` or `head_commit`). The 8 KB
payload limit is not a practical concern for these signals. Continue to monitor
`pg_notify` delivery latency, which can increase above ~1k notifications/sec on some
platforms.

---

## Where a Single Node Genuinely Hurts

### 1. Zero-downtime deployments — the primary practical trigger

A single node cannot be restarted without dropping all SSE connections. Clients reconnect
automatically (using `Last-Event-ID`) and miss nothing, but there is a visible
interruption. For teams with an availability SLA that prohibits this, a second node
behind a load balancer enables rolling restarts.

**This is the first real reason to go multi-node — not throughput.**

### 2. Memory above ~40,000–50,000 clients

Above this range a single JVM heap struggles. Adding a second node halves the client
load per node and is simpler than tuning a single very large JVM.

### 3. Node failure affects all clients simultaneously

If the node crashes, every client loses their stream at once. With two nodes, only
half the client base is affected by any single failure. This is an availability
concern, not a throughput concern.

---

## Conditions for Reconsidering Horizontal Scaling

Horizontal scaling (multiple nodes behind a load balancer) should be revisited when
**any** of the following are true:

| Trigger | Threshold |
|---|---|
| Availability SLA | Zero-downtime deployments are required |
| Concurrent SSE clients | Sustained > 40,000 |
| Restart recovery time | > 60 seconds is operationally unacceptable |
| Node failure blast radius | Losing all clients simultaneously violates SLA |

The `pg_notify` / `LISTEN` architecture already supports multi-node without code changes
(see the **Horizontal Scaling** entry in `design-decision-register.md`). The decision is
simply that the triggers above have not been reached yet.

---

## Recommended Node Sizing (Single Node)

| Clients | vCPU | RAM | PostgreSQL RAM | Notes |
|---|---|---|---|---|
| Up to 1,000 | 1 | 1 GB | 512 MB | Development / small team |
| Up to 10,000 | 2 | 4 GB | 1 GB | Standard production |
| Up to 40,000 | 4 | 8 GB | 2 GB | Large organisation |
| > 40,000 | — | — | — | Consider multi-node |

These are conservative estimates with headroom. Actual memory usage depends on average
signal payload size.

