# CentralIndexer Branching Model — Production Rollout Plan

Reference thresholds from [`Documentation/Design/scalability.md`](../Design/scalability.md).
Metrics available at `GET /metrics`. Incident guidance in [`runbook.md`](runbook.md).

---

## SLA Gates

All phases must meet these thresholds before promotion. Values are from `scalability.md`.

| Metric | Gate | Source key |
|---|---|---|
| `GET /reviews` p95 latency | < 500 ms | `db.get_reviews_p95_ms` |
| `GET /branches` p95 latency | < 200 ms | `branches.typeahead_p95_ms` |
| SSE write latency p95 | < 50 ms | `sse.write_latency_p95_ms` |
| HTTP 5xx error rate | < 0.1 % of requests | access logs |
| HTTP 426 error rate | 0 for clients ≥ `minClientVersion` | access logs |
| DB pool waiting threads | 0 sustained | `db.pool_waiting_threads` |

---

## Phase 0 — Pre-Rollout Prerequisites

Complete all items before deploying any new JAR.

### Checklist

**Data integrity**
- [ ] Run backfill in dry-run mode and confirm no conflicts:
  ```sh
  java -cp ReviewToolCentralIndexer.jar \
       com.kalynx.centralindexer.backfill.BackfillMain --dry-run
  ```
- [ ] Run full backfill and confirm `backfill.progress_pct` returns to `-1` after completion.
- [ ] Spot-check 10+ reviews via `GET /reviews` — each must have a non-empty `repositories` array with `repository_url` set.

**Client version distribution**
- [ ] Query `X-Client-Version` header distribution from access logs.
- [ ] Record the percentage of clients on version ≥ `minClientVersion` (default `2.0.0`).
- [ ] **Do not enable `branch_mode` until ≥ 90% of clients report ≥ `minClientVersion`.**
  - Clients below `minClientVersion` will receive `426 Upgrade Required` once `branch_mode` is enabled.

**Infrastructure**
- [ ] DB pool sized for expected concurrent clients (see table below).
- [ ] At least one replica or standby available for the backfill run.
- [ ] Metrics endpoint verified reachable: `curl http://<host>:8765/metrics`
- [ ] Run smoke test against current production: `tools/validation/smoke-test.sh <URL>`

**Recommended DB pool sizes** (from `scalability.md`):

| Concurrent clients | `database.poolSize` |
|---|---|
| ≤ 1,000 | 5–10 |
| ≤ 10,000 | 10–20 |
| ≤ 40,000 | 20–40 |

---

## Phase 1 — Deploy New JAR (branch_mode disabled)

**Goal:** Verify the new build is a drop-in replacement with no regressions. Branch-mode features stay off.

**Config (`config.json`):**
```json
{
  "indexer": {
    "features": {
      "branchMode": {
        "enabled": false,
        "minClientVersion": "2.0.0"
      }
    }
  }
}
```

**Steps:**
1. Deploy new JAR using the standard restart procedure from `runbook.md §Restart Procedure`.
2. Verify `GET /health` → `{"status":"UP","db":"UP"}`.
3. Verify `GET /metrics` → 200 with all four top-level keys (`sse`, `db`, `branches`, `backfill`).
4. Confirm `X-Indexer-Feature` header is **absent** on all responses.
5. Run smoke test: `tools/validation/smoke-test.sh http://<host>:8765`

**SLA gate:** All metrics thresholds met for 24 hours before proceeding.

**Rollback:** Redeploy the previous JAR using the same restart procedure.

---

## Phase 2 — Enable branch_mode on Canary Node

**Goal:** Expose the branching model feature flag to a subset of traffic and monitor for one week.

**Prerequisites:**
- Phase 1 complete and stable for ≥ 24 hours.
- Client version gate from Phase 0 met (≥ 90% of clients on ≥ `minClientVersion`).

**Config change (canary node only):**
```json
{
  "indexer": {
    "features": {
      "branchMode": {
        "enabled": true,
        "minClientVersion": "2.0.0"
      }
    }
  }
}
```

**Steps:**
1. Update `config.json` on the canary node and restart it (see `runbook.md §Restart Procedure`).
2. Confirm `X-Indexer-Feature: branch_mode` appears on all responses from the canary node.
3. Confirm `GET /events/stream` returns `426` for requests with `X-Client-Version` below `minClientVersion`.
4. Confirm `GET /events/stream` proceeds normally for requests with `X-Client-Version` ≥ `minClientVersion` or no version header.
5. Run smoke test against canary: `tools/validation/smoke-test.sh http://<canary>:8765 --expect-feature-header`
6. Monitor SLA gates continuously for **7 days**.

**What to watch:**
- `db.get_reviews_p95_ms` — spike during client reconnect burst after restart
- `sse.connected_clients_total` — should return to pre-restart count within the client reconnect window (typically 10–60 s)
- 426 rate in access logs — must be zero for clients above `minClientVersion`
- `db.pool_waiting_threads` — must remain at 0 under normal load

**SLA gate:** All thresholds met for 7 continuous days on the canary node before proceeding.

**Rollback:** Set `branchMode.enabled = false` in `config.json` and restart the canary node. No data changes are needed — the flag is pure runtime behaviour.

---

## Phase 3 — Full Rollout

**Goal:** Enable branch_mode on all remaining nodes.

**Prerequisites:**
- Phase 2 stable for ≥ 7 days on canary.
- No open incidents related to the branching model.

**Steps:**
1. Update `config.json` on all remaining nodes to match the Phase 2 canary config.
2. Perform a rolling restart (one node at a time) to avoid a simultaneous reconnect storm.
   - Wait for `sse.connected_clients_total` to recover before restarting the next node.
3. Run smoke test against each node after restart.
4. Monitor SLA gates for 48 hours post-rollout.

**Rollback:** Set `branchMode.enabled = false` on all nodes and perform a rolling restart.

---

## Phase 4 — Shim Removal (after client upgrade threshold met)

**Trigger:** Run this phase only when ≥ 99% of SSE clients report `X-Client-Version` ≥ `minClientVersion` consistently over a 30-day window.

**What can be removed once all clients are upgraded:**

| Component | What to change |
|---|---|
| `ClientVersionFilter` | Remove the `426 Upgrade Required` path; the filter can be deleted entirely once all clients are compliant |
| `branchMode.enabled` config | Hardcode feature as always-on; remove the flag from `config.json` and `FeaturesConfig` |
| `minClientVersion` config | Remove from config once version check is gone |
| `X-Indexer-Feature` header | Optionally remove `FeatureHeaderFilter` wrapping or keep as permanent informational header |

**Safe removal order:**
1. Confirm client version gate (≥ 99% ≥ 2.0.0 for 30 days).
2. Remove `ClientVersionFilter` — deploy and verify no 426 errors in access logs.
3. Simplify `IndexerHttpServer` to remove the feature-flag conditional wiring.
4. Remove `FeaturesConfig`/`BranchModeConfig` and associated config block.
5. Update all tests to remove feature-flag test cases that are no longer relevant.

---

## Rollback Decision Tree

```
Is the problem limited to one node?
├─ Yes → Roll back that node; investigate before rolling back others
└─ No  → Roll back all nodes simultaneously

Is branch_mode the likely cause?
├─ Yes → Set branchMode.enabled=false and restart
└─ No  → Redeploy previous JAR (Phase 1 config was stable)

Did DB pool exhaustion occur?
└─ Increase database.poolSize in config.json and restart (see runbook.md §DB Pool Exhaustion)
```

---

## Post-Launch Validation Checklist

Run after Phase 3 completes, and again 7 days later.

**Endpoint validation**
- [ ] `GET /health` → `{"status":"UP","db":"UP"}` on all nodes
- [ ] `GET /reviews` → 200, non-empty `items` array (if reviews exist), `repository_url` present
- [ ] `GET /branches` → 200, valid `branches` array and `next_cursor`
- [ ] `GET /metrics` → 200, all four keys present, `db.pool_waiting_threads` = 0
- [ ] `GET /events/stream?repository=<owner/repo>` → 200 text/event-stream (upgrade client first)

**Metrics baseline**
- [ ] Record `sse.connected_clients_total` steady-state value
- [ ] Record `db.get_reviews_p95_ms` steady-state value
- [ ] Record `branches.typeahead_p95_ms` steady-state value
- [ ] Confirm `backfill.progress_pct` = -1 (no backfill running)

**Automated smoke test**
```sh
tools/validation/smoke-test.sh http://<host>:8765
```
All checks must pass (exit code 0).

---

## Node Sizing Reference

From `scalability.md`. Scale horizontally when sustained SSE clients exceed 40,000.

| Clients | vCPU | RAM | PostgreSQL RAM |
|---|---|---|---|
| ≤ 1,000 | 1 | 1 GB | 512 MB |
| ≤ 10,000 | 2 | 4 GB | 1 GB |
| ≤ 40,000 | 4 | 8 GB | 2 GB |
| > 40,000 | Multi-node — see `scalability.md §Zero-downtime deployments` | | |
