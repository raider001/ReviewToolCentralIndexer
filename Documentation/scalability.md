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
| Event retention | 7 days default | Configurable; drives storage sizing |

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

### PostgreSQL connection pool — startup thundering herd

The pool holds 10–20 JDBC connections shared across all virtual threads. Under normal
operation this is never a bottleneck: webhooks are infrequent, and replay queries from
staggered client reconnects are fast.

**The problem case:** If the node restarts, all connected clients reconnect within
seconds and all simultaneously issue replay queries (`SELECT WHERE sequence_no > ?`).
With 10,000 clients and 10 pool connections:

- 9,990 virtual threads queue on the pool
- Each query takes ~1–5 ms against a warm PostgreSQL index
- At 10 parallel queries that is ~5,000 ms / 10 = **~50 seconds to drain the queue**

The node is technically correct throughout but appears slow during this window.

**Mitigations (without adding nodes):**
- Increase pool size to 50 for restart resilience (PostgreSQL handles this on modest hardware)
- Add a jittered reconnect delay on the client side (~0–30 seconds random)
- Prioritise new client connections; defer replay until the live path is established

---

### PostgreSQL table size at 10,000 repositories

With 10,000 repositories and a 7-day retention window:

| Events per repo per day | Total rows (7 days) | Assessment |
|---|---|---|
| 10 | ~700,000 | Trivial |
| 100 | ~7,000,000 | Fine with proper indexing |
| 1,000 | ~70,000,000 | Large; index maintenance becomes measurable |

The `(repository, sequence_no)` unique index keeps per-repository replay queries fast
regardless of total table size. The pruning job must run frequently enough to keep
rows in the lower ranges above.

**Table partitioning by repository is not viable at 10,000+ repositories** —
PostgreSQL degrades with more than ~1,000 list partitions. The composite index is the
correct strategy.

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

### `pg_notify` payload size

PostgreSQL limits each notification payload to **8,000 bytes**. The notification carries
the full `ReviewEvent` serialised as JSON — no separate DB query is needed in the LISTEN
thread. A typical event payload is ~300 bytes. The limit is never approached.

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
event payload size and the configured retention window.

