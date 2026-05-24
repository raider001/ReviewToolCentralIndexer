# CentralIndexer Operations Runbook

Reference thresholds are from [`Documentation/Design/scalability.md`](../Design/scalability.md).
Metrics are available at `GET /metrics` on the running indexer.

---

## Quick Reference

| Symptom | First check | Metric key |
|---|---|---|
| High CPU | SSE client count | `sse.connected_clients_total` |
| Slow review loads | Reviews query latency | `db.get_reviews_p95_ms` |
| Slow branch typeahead | Branches query latency | `branches.typeahead_p95_ms` |
| Connection errors / 503s | DB pool saturation | `db.pool_waiting_threads` |
| Missing SSE events | Event emission rate | `sse.writers_per_second` |

---

## Incident Scenarios

### 1. High CPU — SSE Fan-Out

**Indicators:**
- Node CPU > 60% sustained
- `sse.connected_clients_total` > 10 000
- A single repository has hundreds of concurrent watchers

**Cause:** At 100 events/second fan-out to 10 000 watchers on one repository, each event
triggers ~10 000 kernel writes, consuming ~1–2 CPU cores (see `scalability.md` §SSE write
throughput).

**Mitigations (in order):**
1. Check `sse.connected_clients_total`. If approaching 40 000, plan horizontal scaling.
2. Identify the hot repository via application logs (`SSE client connected` entries).
3. Long-term: add a second node behind a load balancer for rolling restarts and load
   distribution (see `scalability.md` §Zero-downtime deployments).

---

### 2. Reconnect Storm — Slow GET /reviews

**Indicators:**
- `db.get_reviews_p95_ms` > 500 ms
- `db.pool_waiting_threads` > 0 sustained
- Spike correlates with a recent server restart

**Cause:** All SSE clients reconnect simultaneously after a restart, each calling
`GET /reviews` before re-opening the SSE stream. This is a thundering herd against
`reviews_index`.

**Mitigations:**
1. **Immediate**: Increase `database.poolSize` in `config.json` and restart (pool size 10
   is adequate for ≤1 000 clients; scale to 20–40 for larger reconnect bursts).
2. **Client-side**: Configure SSE clients to use jittered reconnect backoff (2–10 seconds
   random delay). This flattens the burst across time.
3. **Read replica**: Under sustained high reconnect rates, consider routing `GET /reviews`
   to a PostgreSQL read replica.

**Recommended pool sizes** (from `scalability.md`):

| Concurrent clients | `database.poolSize` |
|---|---|
| ≤ 1 000 | 5–10 |
| ≤ 10 000 | 10–20 |
| ≤ 40 000 | 20–40 |

---

### 3. DB Pool Exhaustion

**Indicators:**
- `db.pool_waiting_threads` > 0 for more than a few seconds
- HTTP 503 responses on `/reviews` or `/branches`
- Latency spikes across all DB-backed endpoints

**Cause:** More concurrent requests than the pool has connections. Either the pool is
undersized for the workload, or connections are being held for too long (slow query,
lock contention).

**Mitigations:**
1. Check `db.pool_active_connections` — if equal to pool capacity, the pool is exhausted.
2. Increase `database.poolSize` in `config.json` and restart.
3. Check PostgreSQL `pg_stat_activity` for long-running queries or locks.
4. If `/reviews` queries are slow, run `EXPLAIN ANALYZE` against `reviews_index`:
   ```sql
   EXPLAIN ANALYZE SELECT * FROM reviews_index WHERE status = 'open';
   ```
   Ensure the `status` index is being used.

---

### 4. Backfill Issues

**Indicators:**
- `backfill.progress_pct` stuck or shows -1 when a backfill should be running
- `GET /reviews` returns empty `repositories` arrays after a migration
- `BackfillMain` exits with code 1 (conflicts reported)

**Diagnosis steps:**
1. Run with `--dry-run` to inspect what the tool would do without writing:
   ```sh
   java -cp ReviewToolCentralIndexer.jar \
        com.kalynx.centralindexer.backfill.BackfillMain --dry-run
   ```
2. Check the report: `conflictReviewIds` lists reviews in `review_branches` with no
   resolvable repository or branch record (INNER JOIN missed them).
3. Verify the referenced `branches` and `repositories` rows exist for conflicting reviews.
4. Re-run without `--dry-run` once the data is corrected.

**Recovery:**
```sh
# Verify first
java -cp ReviewToolCentralIndexer.jar \
     com.kalynx.centralindexer.backfill.BackfillMain --dry-run

# Apply if clean
java -cp ReviewToolCentralIndexer.jar \
     com.kalynx.centralindexer.backfill.BackfillMain
```

---

## Restart Procedure

1. Drain SSE clients gracefully (inform clients via monitoring / status page if possible).
2. `GET /health` — confirm `db: UP` before restarting.
3. Deploy new JAR / config change.
4. Restart process. The server accepts connections immediately.
5. Monitor `sse.connected_clients_total` — it should climb back to steady state within
   the client reconnect window (typically 10–60 seconds with jitter).
6. Monitor `db.get_reviews_p95_ms` during the reconnect burst; alert threshold 500 ms.

---

## Key Config Parameters

| Parameter | Default | Effect |
|---|---|---|
| `database.poolSize` | 10 | Max concurrent DB connections |
| `server.port` | 8765 | HTTP listen port |
| `auth.enabled` | false | Bearer token enforcement on `/events/*` and `/branches` |
