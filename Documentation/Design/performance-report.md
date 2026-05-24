# CentralIndexer Performance Report

**Date:** <!-- e.g. 2026-05-22 -->
**Server version / commit:** <!-- git rev-parse --short HEAD -->
**Environment:** <!-- e.g. Local Docker, 2 vCPU / 4 GB RAM, PostgreSQL 16.3 -->
**Seed dataset:** 100 repos · 50 000 branches · 10 000 reviews (from `tools/benchmarks/seed-data.sql`)

---

## Thresholds (from `scalability.md` and M6 acceptance criteria)

| Metric | Threshold | Rationale |
|---|---|---|
| SSE connection refused rate | < 1% | Server must accept connections at target VU count |
| SSE first-byte / connect latency p95 | < 500 ms | Client connect feel |
| `GET /reviews` p95 under reconnect storm | < 500 ms | From M6 acceptance criteria |
| `GET /branches` typeahead p95 (50 000-branch dataset) | < 500 ms | Scaled up from BranchesLoadIT 200 ms baseline |
| CPU during SSE fan-out (hot-repo scenario) | < 60% | From M6 acceptance criteria (`scalability.md` §SSE write throughput) |

---

## 1. SSE Fan-Out — Connection Capacity

**Script:** `tools/benchmarks/sse-fanout-benchmark.js`
**Command:**
```sh
k6 run --vus 100 --duration 60s \
       -e BASE_URL=http://localhost:8765 \
       sse-fanout-benchmark.js
```

| Metric | Measured | Pass? |
|---|---|---|
| http_req_failed rate | <!-- e.g. 0.2% --> | <!-- ✅ / ❌ --> |
| sse_connect_latency_ms p95 | <!-- e.g. 120 ms --> | <!-- ✅ / ❌ --> |
| Active connections sustained | <!-- e.g. 100 --> | <!-- ✅ / ❌ --> |

**Notes:** <!-- any observations, tuning, or anomalies -->

---

## 2. GET /reviews — Reconnect Storm

**Script:** `tools/benchmarks/reviews-reconnect-storm.js`
**Command:**
```sh
k6 run --vus 200 --iterations 2000 \
       -e BASE_URL=http://localhost:8765 \
       reviews-reconnect-storm.js
```

| Metric | Measured | Pass? |
|---|---|---|
| http_req_duration p95 | <!-- e.g. 180 ms --> | <!-- ✅ / ❌ --> |
| http_req_failed rate | <!-- e.g. 0% --> | <!-- ✅ / ❌ --> |
| DB pool max active connections | <!-- e.g. 8/10 --> | <!-- ✅ / ❌ --> |

**Notes:** <!-- e.g. pool size tuning, jitter observations -->

---

## 3. GET /branches — Typeahead Latency

**Script:** `tools/benchmarks/branches-typeahead-benchmark.js`
**Command:**
```sh
k6 run --vus 50 --duration 60s \
       -e BASE_URL=http://localhost:8765 \
       branches-typeahead-benchmark.js
```

| Metric | Measured | Pass? |
|---|---|---|
| http_req_duration p95 | <!-- e.g. 95 ms --> | <!-- ✅ / ❌ --> |
| http_req_duration p99 | <!-- e.g. 140 ms --> | <!-- ✅ / ❌ --> |
| http_req_failed rate | <!-- e.g. 0% --> | <!-- ✅ / ❌ --> |

**Notes:** <!-- e.g. index scan plan, any slow-query log hits -->

---

## 4. Java SSE Client Harness — First-Event Latency

**Tool:** `tools/benchmarks/java-sse-client-harness/`
**Command:**
```sh
cd tools/benchmarks/java-sse-client-harness
mvn -q package
java -jar target/java-sse-client-harness.jar \
     --url http://localhost:8765 \
     --clients 200 --duration 60 --repos 100
```

| Metric | Measured | Pass? |
|---|---|---|
| First-event latency p50 | <!-- e.g. 35 ms --> | — |
| First-event latency p95 | <!-- e.g. 120 ms --> | <!-- ✅ / ❌ --> |
| First-event latency p99 | <!-- e.g. 200 ms --> | — |
| Total events received | <!-- e.g. 14 200 --> | — |

**Notes:** <!-- e.g. how events were generated during the run -->

---

## 5. Resource Utilisation

Captured during benchmark runs. Use `docker stats`, `htop`, or your monitoring stack.

| Resource | Idle | Peak (SSE storm) | Peak (reconnect storm) |
|---|---|---|---|
| JVM heap used | | | |
| CPU (all cores) | | | |
| DB connections active | | | |
| Network TX | | | |

---

## 6. Recommendations

<!-- Complete after running benchmarks. Example entries: -->
- DB pool default size (`database.poolSize`): **<!-- e.g. 10 -->** connections.
  Increase to **<!-- e.g. 20 -->** during planned restart windows.
- Heap recommendation for 10 000 clients: **<!-- e.g. 512 MB -->** (`-Xmx512m`).
- Client reconnect jitter: minimum **<!-- e.g. 2s -->** random backoff recommended
  to avoid `GET /reviews` thundering-herd after server restart.

---

## Appendix: How to Reproduce

1. Start PostgreSQL (e.g. `docker-compose up -d db`)
2. Start the indexer with benchmarks config:
   ```sh
   java -jar ReviewToolCentralIndexer.jar
   ```
3. Seed data:
   ```sh
   psql "$DATABASE_URL" -f tools/benchmarks/seed-data.sql
   ```
4. Run each k6 script as shown above. Results are printed to stdout; save with
   `| tee results-$(date +%Y%m%d).txt`.
5. Run the Java harness while a k6 script is active to get event-delivery
   latency under concurrent connection load.
