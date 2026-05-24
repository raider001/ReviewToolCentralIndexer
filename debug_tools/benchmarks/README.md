# CentralIndexer Benchmark Scripts

Performance benchmarks for the Central Indexer branching model (M6).
All thresholds are derived from [`Documentation/Design/scalability.md`](../../Documentation/Design/scalability.md).

---

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| [k6](https://k6.io/docs/getting-started/installation/) | ≥ 0.50 | HTTP load scripts |
| Java | ≥ 21 | SSE client harness |
| Maven | ≥ 3.9 | Build the harness jar |
| psql | any | Seed data |
| Docker (optional) | any | Run PostgreSQL locally |

---

## Quick Start

### 1. Seed the database

Start PostgreSQL and apply the schema, then load benchmark data:

```sh
# Using docker-compose from the project root:
docker-compose up -d

psql "$DATABASE_URL" -f seed-data.sql
```

Expected output:
```
    tbl        | count
---------------+-------
 repositories  |   100
 branches      | 50000
 reviews_index | 10000
 review_branches| 10000
```

### 2. Start the indexer

```sh
java -jar ReviewToolCentralIndexer.jar
```

### 3. Run benchmarks

Set `BASE_URL` to the indexer's address. If auth is enabled, also set `BEARER_TOKEN`.

```sh
export BASE_URL=http://localhost:8765
export BEARER_TOKEN=""   # leave empty if auth.enabled=false
```

#### SSE fan-out (100 concurrent SSE connections, 60 s)

```sh
k6 run --vus 100 --duration 60s \
       -e BASE_URL="$BASE_URL" -e BEARER_TOKEN="$BEARER_TOKEN" \
       sse-fanout-benchmark.js
```

**Pass criteria:** `http_req_failed < 1%`, `sse_connect_latency_ms p(95) < 500 ms`

#### GET /reviews reconnect storm (200 VUs, 2 000 iterations with jitter)

```sh
k6 run --vus 200 --iterations 2000 \
       -e BASE_URL="$BASE_URL" -e BEARER_TOKEN="$BEARER_TOKEN" \
       reviews-reconnect-storm.js
```

**Pass criteria:** `http_req_duration p(95) < 500 ms`, `http_req_failed < 1%`

#### GET /branches typeahead (50 VUs, 60 s)

```sh
k6 run --vus 50 --duration 60s \
       -e BASE_URL="$BASE_URL" -e BEARER_TOKEN="$BEARER_TOKEN" \
       branches-typeahead-benchmark.js
```

**Pass criteria:** `http_req_duration p(95) < 500 ms`, `http_req_failed < 1%`

#### Java SSE client harness (200 virtual SSE clients, 60 s)

```sh
cd java-sse-client-harness
mvn -q package
java -jar target/java-sse-client-harness.jar \
     --url "$BASE_URL" \
     --clients 200 --duration 60 --repos 100
```

**Pass criteria:** first-event latency p95 < 500 ms when events are being generated.

---

## Recording Results

Record measured values in the performance report template:
[`Documentation/Design/performance-report.md`](../../Documentation/Design/performance-report.md)

Save raw k6 output for comparison:
```sh
k6 run ... | tee results-$(date +%Y%m%d-%H%M).txt
```

---

## Seed Data Details

`seed-data.sql` inserts:
- **100 repositories** under `bench-org`
- **50 000 branches** (500 per repo, names `feature/bench-0001` … `feature/bench-0500`)
- **10 000 reviews** (`bench-review-1` … `bench-review-10000`) with one branch mapping each
- JSONB `repositories` column backfilled so `GET /reviews` returns complete data

All rows use the `bench-org` owner and `bench-review-*` prefix so they can be identified
and cleaned up without affecting production data.

To remove seed data:
```sql
DELETE FROM review_branches WHERE review_id LIKE 'bench-review-%';
DELETE FROM reviews_index   WHERE review_id LIKE 'bench-review-%';
DELETE FROM branches        WHERE owner = 'bench-org';
DELETE FROM repositories    WHERE owner = 'bench-org';
```

---

## File Index

| File | Purpose |
|---|---|
| `seed-data.sql` | Populates benchmark dataset in PostgreSQL |
| `sse-fanout-benchmark.js` | k6 script: SSE connection capacity |
| `reviews-reconnect-storm.js` | k6 script: GET /reviews under reconnect burst |
| `branches-typeahead-benchmark.js` | k6 script: GET /branches prefix query latency |
| `java-sse-client-harness/` | Java tool: N virtual SSE clients, first-event latency |
