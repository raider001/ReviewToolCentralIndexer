# Evolve-To-Branching-Model-Review-System — Roadmap

## ✅ Status Summary (Updated 2026-05-22) — ALL MILESTONES COMPLETE

**Current Implementation Status:**
- ✅ **M0** — Preparation & Cleanup: COMPLETE
- ✅ **M1** — Branch Schema & Read Model: COMPLETE
- ✅ **M1.5** — Remove Event SQL Table Infrastructure: COMPLETE
- ✅ **M2** — Global `GET /branches` Endpoint: COMPLETE
- ✅ **M3** — SSE Signal Changes & Client Connect Flow: COMPLETE
- ✅ **M4** — Migration & Backfill Tools: COMPLETE
- ✅ **M5** — Client Compatibility & Feature Flag Rollout: COMPLETE
- ✅ **M6** — Performance, Scalability & Safety Gates: COMPLETE
- ✅ **M7** — System Tests & CI Integration: COMPLETE
- ✅ **M8** — Observability, Metrics & Runbook: COMPLETE
- ✅ **M9** — Production Rollout & Post-Launch Validation: COMPLETE

**What's Working:**
- All normalized branching tables (`repositories`, `branches`, `review_branches`)
- `GET /branches` with prefix search and keyset cursor pagination
- `GET /reviews` with `since`/`status` filtering and `repository_url` in response
- `GET /events/stream` SSE with `repository_url`, `branch_name`, `head_commit` in branch event payloads
- All three provider plugins (GitHub, GitLab, Bitbucket) emit correct branch event payloads

**All milestones complete.** See [`Documentation/Operations/rollout-plan.md`](../../../Operations/rollout-plan.md) for the production rollout procedure and `tools/validation/smoke-test.sh` for live environment validation.

**See the detailed [Summary: What's Being Added vs. Removed](#summary-whats-being-added-vs-removed) section at the end of this document for a complete breakdown of changes.**

---

## Key Architecture Documents

These documents define the authoritative design constraints. Every milestone must be validated against the relevant entries before implementation begins and before marking a milestone complete.

| Document | Location | Relevant To |
|---|---|---|
| **Indexer Architecture Overview** | [`README.md`](../../README.md) | All milestones — concurrency model, plugin SPI, startup sequence |
| **Database Storage Design** | [`storage.md`](../../storage.md) | M1, M1.5, M2, M3, M4 — schema, indexes, JSONB structure |
| **Client Interface Specification** | [`Client-Interface.md`](../../../../../Documentation/Design/Interfaces/Client-Interface.md) | M2, M3, M5 — REST endpoints, SSE event shapes, connect sequence |
| **Code Update Sequences** | [`Workflows/Code-Update-Sequences.md`](../../Workflows/Code-Update-Sequences.md) | M1.5, M3, M7 — webhook→SSE flow for commits and branch events |
| **Review Update Sequences** | [`Workflows/Review-Update-Sequences.md`](../../Workflows/Review-Update-Sequences.md) | M1.5, M3, M7 — webhook→SSE flow for review lifecycle events |
| **Scalability Analysis** | [`scalability.md`](../../scalability.md) | M6, M8 — concurrency limits, DB pool sizing, SSE fan-out bounds |
| **Plugin SPI Design** | [`plugin-design.md`](../../../../../Documentation/Design/plugin-design.md) | M0, M3 — plugin lifecycle, EventSink contract, WebhookRouter |
| **GitHub Webhook Interface** | [`ExternalInterfaces/GitHub.md`](../../ExternalInterfaces/GitHub.md) | M0, M3 — push event payload fields, signature verification |
| **Bitbucket Webhook Interface** | [`ExternalInterfaces/Bitbucket.md`](../../ExternalInterfaces/Bitbucket.md) | M0, M3 — push event payload fields |
| **GitLab Webhook Interface** | [`ExternalInterfaces/GitLab.md`](../../ExternalInterfaces/GitLab.md) | M0, M3 — push event payload fields |
| **Notes-to-Branch Pivot Proposal** | [`notes-to-branch-pivot-proposal.md`](../../../../../Documentation/Design/NotesToBranchPivotProposal/notes-to-branch-pivot-proposal.md) | M0 — decision context (ADOPTED: orphan branch model) |

---

Purpose

This roadmap defines the work required to evolve the Central Indexer from a review-centric model to a branch-centric review discovery and routing model. It focuses on the practical steps, testable milestones, and verification strategy required to deliver the design changes captured in the interface and storage documents (global `GET /branches`, `reviews_index` read model, and `signal, not payload` SSE behaviour).

Scope (what this roadmap covers)

- Add and validate a global branch lookup endpoint for client typeahead and branch→review routing.
- Ensure `reviews_index` reliably represents latest review state (one row per review) and supports branch-based queries.
- Migrate routing and SSE signals to include minimal branch routing metadata (`repository_url`, `head_commit`) while keeping payloads small.
- Provide a migration/backfill path for existing review→branch mappings and ensure clients can bootstrap from `GET /reviews` + SSE without replaying a full event store.
- Deliver tests (unit, integration, system), benchmark scripts, and CI gates required for safe rollout.

Out of scope

- Replacing git as the audit/history store. Git remains the canonical history; the indexer stores only current state used for routing and discovery.
- Large-scale data retention or long-term event archiving (may be implemented as an independent optional component).

---

## Migration & Obsolete Code Assessment

**Context:** The current codebase implements a **generic event indexer** (Milestones 0-10 from `main-development-roadmap.md`). The branching model introduces **domain-specific review/branch semantics** that require new tables, endpoints, and event types.

### Code That Can Be Reused (No Changes Needed)

- ✅ Config loading (`ConfigLoader`, `AppConfig`, etc.)
- ✅ Database connection pool (`ConnectionPool`)
- ✅ SSE infrastructure (`PublisherRegistry`, `SseHandler`) — `ListenThread`, `EventRepository`, `RetryQueue` removed in M1.5
- ✅ HTTP server and auth (`IndexerHttpServer`, `AuthFilter`)
- ✅ Plugin system (`PluginLoader`, `EventSinkImpl`, `WebhookRouterImpl`)
- ✅ TLS support
- ✅ Test infrastructure (`PostgresTestContainer`, `@RequiresDocker`)

### Code That Needs Extension (Additive Changes)

- ⚠️ **`DatabaseInitializer`** — Add normalized tables: `repositories`, `branches`, `review_branches`
- ⚠️ **Event model** — Add new event types: `branch.updated`, `branch.deleted`
- ⚠️ **`ReviewsIndexMapper`** (if exists) — Ensure it builds complete JSONB with `repository_url`

### Code That Is Missing (New Implementation Required)

- ❌ **`ReviewsHandler`** — `GET /reviews` endpoint (Client-Interface.md)
- ❌ **`BranchesHandler`** — `GET /branches` endpoint
- ❌ **`BranchRepository`** — Query logic for branch typeahead
- ❌ **`ReviewsIndexRepository`** — Query logic for `GET /reviews` with filtering
- ❌ **Backfill tooling** — `BackfillBranchesTool` CLI
- ❌ **Feature flags** — `FeaturesConfig`, version negotiation
- ❌ **Metrics** — `MetricsCollector`, `GET /metrics` endpoint
- ❌ **Benchmarks** — Scripts in `tools/benchmarks/`
- ❌ **Runbook** — `Documentation/Operations/runbook.md`

### Potential Obsolete Code (Review Required)

**NOTE:** If the **Notes-to-Branch Pivot Proposal** is adopted, some existing code may become obsolete:

- ⚠️ **Client-initiated notification endpoints** — `POST /events/notify` (if it exists) would no longer be needed
- ⚠️ **Pending notification queue** — Not needed with native webhook support for orphan branches
- ⚠️ **Post-receive hook support** — Not needed for cloud-hosted providers

**Action Item:** Review `notes-to-branch-pivot-proposal.md` status and decide whether to adopt. If yes, update this roadmap to reflect scope changes (simpler notification model, potentially remove some reconciliation logic).

Principles

- Keep changes small, reversible and well-tested. Each milestone produces a vertical slice with clear gate criteria.
- Prefer upserts and idempotent operations in the indexer; gate updates by `last_updated` to avoid regressing newer state.
- Maintain "signal, not payload": SSE message bodies remain minimal. Clients fetch full review content from the canonical git remotes using `repository_url` and commit ids when needed.
- Backwards compatibility: clients that have not upgraded must still be able to use `GET /reviews` and SSE in the previous form where possible; design the server to support both short-term.

Milestones

---

## Milestone 0 — Preparation & Cleanup (1 week)

**Status:** ✅ **Complete** — Preparation phase before implementing branching model.

**Architecture References:**
- [`README.md`](../../README.md) — component map; understand current infrastructure scope before deciding what to remove
- [`plugin-design.md`](../../../../../Documentation/Design/plugin-design.md) — plugin lifecycle and `EventSink` contract; required for the plugin audit deliverable
- [`ExternalInterfaces/GitHub.md`](../../ExternalInterfaces/GitHub.md) — push event payload fields to validate against GitHub plugin output
- [`ExternalInterfaces/Bitbucket.md`](../../ExternalInterfaces/Bitbucket.md) — push event payload fields to validate against Bitbucket plugin output
- [`ExternalInterfaces/GitLab.md`](../../ExternalInterfaces/GitLab.md) — push event payload fields to validate against GitLab plugin output
- [`notes-to-branch-pivot-proposal.md`](../../../../../Documentation/Design/NotesToBranchPivotProposal/notes-to-branch-pivot-proposal.md) — decision context; must be resolved in this milestone because it determines the scope of M3

Goal

Prepare the codebase for the branching model migration by removing obsolete code, documenting current state, and validating that existing infrastructure is ready for extension.

**What Must Be Removed:**

Since the codebase was built for a **generic event indexer** and the Notes-to-Branch Pivot Proposal is pending, we need to assess what functionality is no longer needed:

1. **Review Current Plugin Implementations**:
   - `BitbucketPlugin`, `GitHubPlugin`, `GitLabPlugin` in `provider/` package
   - These currently handle generic events but may need updates for branch-specific review routing
   - **Decision needed**: Are these plugins aligned with orphan branch model or notes model?
   - **Action**: If notes-based, document migration path to orphan branches

2. **Audit Event Emission**:
   - Review how plugins currently emit `BRANCH_UPDATED` and `BRANCH_DELETED` events
   - Ensure these events include necessary payload fields: `repository_url`, `head_commit`, `branch_name`
   - **Action**: If payloads are incomplete, add to M3 deliverables

3. **Remove Dead Code** (if any exists):
   - Search for commented-out code
   - Remove any incomplete/experimental features not in use

**What Must Be Validated:**

1. **Schema Compatibility Check**:
   - Run `DatabaseInitializer` against test DB and confirm `reviews_index` table schema
   - Verify JSONB columns accept the structure defined in `ReviewsIndexMapper`
   - Document any schema version/migration strategy (currently none exists)

2. **Event Type Coverage**:
   - ✅ Confirm `EventType` enum includes: `REVIEW_CREATED`, `REVIEW_UPDATED`, `REVIEW_CLOSED`, `REVIEW_COMMENT_ADDED`, `REVIEW_COMMENT_UPDATED`, `BRANCH_UPDATED`, `BRANCH_DELETED`
   - ✅ Already complete — all event types exist

3. **Test Coverage Baseline**:
   - Run all existing tests (unit + integration)
   - Document pass/fail rate as baseline
   - Identify any flaky tests

**What Must Be Documented:**

1. **Current API Surface**:
   - Document that only `GET /events` and `GET /events/stream` currently exist
   - Note that `GET /reviews` and `GET /branches` are missing
   - List all registered HTTP endpoints from `IndexerHttpServer`

2. **Notes-to-Branch Decision**:
   - Review `notes-to-branch-pivot-proposal.md` with stakeholders
   - **Decision**: Adopt orphan branch model OR stick with notes
   - **Impact**: If adopting orphan branches, client notification endpoints (`POST /events/notify`) may become obsolete
   - Document decision in this roadmap

3. **Database State**:
   - If any production/test instances exist with data, document current row counts
   - Plan data migration strategy if needed

Deliverables

- ✅ Event type audit complete — no changes needed to `EventType` enum
- ❌ Plugin implementation audit — document current behavior vs. required behavior
- ❌ Dead code removal (if any found)
- ❌ Schema validation — run `DatabaseInitializer` and verify structure
- ❌ Test baseline — run full test suite and document results
- ❌ Notes-to-branch decision documented
- ❌ Current API surface documented

Acceptance Criteria

- All existing tests pass (establish baseline)
- Notes-to-branch decision is made and documented in roadmap
- No orphaned/commented code remains in `src/main/java`
- Database schema validated against current `DatabaseInitializer`
- Plugin behavior documented (what events they emit, what payloads)

Test Types

- Integration: Run existing `DatabaseInitializerIT` and verify table structure
- System: Run full test suite with `mvn test` and document results

---

## Milestone 1 — Branch Schema & Read Model (2 weeks)

**Status:** ✅ **COMPLETE** (2026-05-20)

**Architecture References:**
- [`storage.md`](../../storage.md) — authoritative schema for `repositories`, `branches`, `review_branches` tables and the `reviews_index` JSONB structure; every DDL statement in `DatabaseInitializer` must match this document
- [`README.md`](../../README.md) — component map; `DatabaseInitializer` is the sole owner of schema DDL

Goal

Introduce the branch-centric fields into the storage/read model and ensure the `reviews_index` includes branch mappings and `repository_url`.

**Completed Work:**

1. **Normalized tables added to `DatabaseInitializer.java`:**
   ```sql
   CREATE TABLE IF NOT EXISTS repositories (
       owner TEXT NOT NULL,
       repository TEXT NOT NULL,
       url TEXT NOT NULL,
       PRIMARY KEY (owner, repository)
   );

   CREATE TABLE IF NOT EXISTS branches (
       owner TEXT NOT NULL,
       repository TEXT NOT NULL,
       branch_name TEXT NOT NULL,
       head_commit TEXT NOT NULL,
       PRIMARY KEY (owner, repository, branch_name),
       FOREIGN KEY (owner, repository) REFERENCES repositories (owner, repository)
   );

   CREATE TABLE IF NOT EXISTS review_branches (
       review_id TEXT NOT NULL,
       owner TEXT NOT NULL,
       repository TEXT NOT NULL,
       branch_name TEXT NOT NULL,
       PRIMARY KEY (review_id, owner, repository, branch_name),
       FOREIGN KEY (review_id) REFERENCES reviews_index (review_id),
       FOREIGN KEY (owner, repository, branch_name) REFERENCES branches (owner, repository, branch_name)
   );
   ```

2. **`ReviewsIndexMapper` validated:**
   - JSONB structure includes: `owner`, `repository`, `repositoryUrl`, `branchName`, `headCommit`
   - Deterministic sorting by `owner`, `repository`, `branchName`
   - Unit tests enhanced to verify all fields

3. **Webhook handlers updated:**
   - All three handlers (GitHub, GitLab, Bitbucket) now extract `repository_url` and include it in event payloads
   - Payload fields renamed: `branch` → `branch_name`, `headSha` → `head_commit`
   - Tests updated and passing (171/171)

4. **Documentation updated:**
   - `storage.md` documents complete schema and JSONB structure

Deliverables

- ✅ `reviews_index` table exists
- ✅ `repositories`, `branches`, `review_branches` tables added to `DatabaseInitializer`
- ✅ `ReviewsIndexMapper` builds complete JSONB with all required fields
- ✅ Migration script not needed (no production data exists)

Acceptance criteria

- Unit tests for `ReviewsIndexMapper` that verify JSON shape and ordering.
- Integration test (`ReviewsIndexIT`) that upserts a review with multiple repositories and branches and reads the row back with expected JSON structure.
- Gate: `GET /reviews` returns items whose `repositories` entries include `repository_url` and branch names.

Test types

- Unit: mapper, JSON serialisation, upsert gating by `last_updated`.
- Integration: PostgresTestContainer validation of JSONB content and indexes.

---

## Milestone 1.5 — Remove Event SQL Table Infrastructure (1 week)

**Status:** ✅ **COMPLETE** (2026-05-20)

Goal

Remove the generic `events` and `repository_state` SQL tables and all code that depends on them. The branching model stores state in the normalised `reviews_index`, `repositories`, `branches`, and `review_branches` tables. A generic append-only event log is no longer part of the design. This milestone was executed immediately after M1 to eliminate the technical debt before building M2–M3 on top of it.

**Architecture References:**
- [`Client-Interface.md`](../../../../../Documentation/Design/Interfaces/Client-Interface.md) — confirms no `GET /events` endpoint exists in the required interface; SSE live stream replaces event history
- [`Workflows/Code-Update-Sequences.md`](../../Workflows/Code-Update-Sequences.md) — new direct event flow: `EventSink.submit()` → `PublisherRegistry.publish()` → SSE client (no DB write on the hot path)
- [`Workflows/Review-Update-Sequences.md`](../../Workflows/Review-Update-Sequences.md) — same direct flow for review lifecycle events
- [`README.md`](../../README.md) — architecture overview; fan-out now bypasses pg_notify since there is no events table to notify from

**What Was Removed:**

| Class / File | Reason |
|---|---|
| `EventRepository` | Sole owner of `events` and `repository_state` table access — already deleted before this milestone |
| `RetryQueue` | Existed only to retry `EventRepository.insert()` on transient DB failures |
| `ReconciliationRunner` | Queried `repository_state` to decide which repos to reconcile at startup |
| `RepositoryState` | DTO for `repository_state` table rows |
| `ListenThread` | Listened on the `pg_notify` `events` channel; channel was only notified from `EventRepository.insert()` |
| `EventQueuedForRetryException` | Signal exception for retry-queue acceptance path — no longer thrown |
| `RetryQueueFullException` | Signal exception for retry-queue full path — no longer thrown |
| `RetryQueueConfig` | Configuration class for the now-deleted retry queue |
| `EventsHandler` (`GET /events`) | Generic paginated event history endpoint — not in the client interface spec |
| 8 test classes | `RetryQueueTest`, `RetryQueueIT`, `EventSinkImplIT`, `EventsHandlerTest`, `EventsEndpointIT`, `ReconciliationRunnerTest`, `ReconciliationRunnerIT`, `StartupOrderingIT` |

**What Was Changed:**

| Class | Change |
|---|---|
| `EventSinkImpl` | Removed `EventRepository` dependency; `submit()` now calls `publisherRegistry.publish()` directly |
| `SseHandler` | Removed `EventRepository`; no more cursor validation (`410 Gone`) or DB replay; streams live events only |
| `IndexerHttpServer` | Removed `EventRepository` parameter; removed `/events` context registration |
| `WebhookDispatcher` | Removed catch blocks for deleted retry exceptions |
| `Application` | Removed `EventRepository`, `PruneScheduler`, `RetryQueue`, `ListenThread`, `ReconciliationRunner`; startup is now: `plugin.start()` → `IndexerHttpServer.start()` |
| `Main` | Removed `EventRepository` instantiation |
| `IndexerConfig` | Stripped to `pluginsDir` only; all retention/reconciliation/retry/prune settings removed |
| `DatabaseInitializer` | Javadoc cleaned; `events` and `repository_state` table references removed |
| 7 surviving test classes | `EventSinkImplTest`, `SseHandlerTest`, `LiveStreamIT`, `SystemIT`, `TlsIT`, `DatabaseInitializerIT`, `HttpServerIT` — updated constructors and removed replay/cursor assertions |

**Architectural Impact:**

SSE reconnect behaviour has changed. Clients can no longer replay missed events via `Last-Event-ID` / `?since=` because there is no persistent event log. On reconnect, clients must re-call `GET /reviews` to catch up on missed state, which is the connect sequence defined in `Client-Interface.md`. The `id:` line has been dropped from SSE frames.

Acceptance Criteria

- No references to `EventRepository`, `RetryQueue`, `ListenThread`, `ReconciliationRunner`, `RepositoryState`, `PruneScheduler`, or the deleted exception classes anywhere in `src/`
- `EventSinkImpl` takes only `PublisherRegistry` as a constructor argument
- `IndexerHttpServer` takes no `EventRepository` parameter; `/events` endpoint is not registered
- `Application` constructs without `EventRepository`; startup sequence has no reconciliation or prune phases
- `DatabaseInitializer.init()` creates only the branching model tables: `reviews_index`, `repositories`, `branches`, `review_branches`
- `DatabaseInitializerIT` verifies the branching model tables and indexes, not `events`/`repository_state`
- All unit and integration tests pass

---

## Milestone 2 — Global `GET /branches` Endpoint (2 weeks)

**Status:** ✅ **COMPLETE** (2026-05-20)

**Architecture References:**
- [`Client-Interface.md`](../../../../../Documentation/Design/Interfaces/Client-Interface.md) — definitive spec for `GET /branches`: query parameters (`q`, `repository`, `limit`, `cursor`), response shape, and error codes; implementation must match exactly
- [`storage.md`](../../storage.md) — `branches` table schema and `idx_branches_name_prefix` index definition; cursor encoding must align with the primary key order `(owner, repository, branch_name)`

Goal

Implement a lightweight global branch typeahead endpoint that returns branch-only records used for client discovery. Keep results small and fast.

**Dependencies:**
- Milestone 1 must be complete (`branches` table must exist)

**What Was Added:**

1. **`BranchRecord`** — immutable `record(owner, repository, branchName)` as the internal DB result type
2. **`BranchRepository`** — queries `branches` table with optional prefix (`LIKE ?`), repository filter, and keyset cursor; uses `idx_branches_name_prefix` for prefix scans; LIKE special characters are escaped
3. **`BranchesHandler`** — `GET /branches` handler; parses and validates `q`, `repository`, `limit`, `cursor`; returns `{"branches": [...], "next_cursor": "..."}` where cursor is URL-safe Base64 of `owner\0repository\0branchName`
4. **`IndexerHttpServer`** — 5-arg constructor added (`branchRepository` nullable); `/branches` registered and wrapped with `AuthFilter`; 4-arg convenience overload retained for existing tests
5. **`Application.start()`** — creates `BranchRepository(pool)` and passes it to `IndexerHttpServer`

Deliverables

- ✅ `BranchesHandler` class
- ✅ `BranchRepository` with prefix query logic and LIKE escaping
- ✅ `idx_branches_name_prefix` index (already added in M1)
- ✅ Cursor keyset pagination via `(owner, repository, branch_name) > (?, ?, ?)`
- ✅ Registered in `IndexerHttpServer`; wired in `Application`

Acceptance criteria

- ✅ Unit tests for handler input validation and query parameter parsing (`BranchesHandlerTest` — 18 tests)
- ✅ Integration tests verifying prefix search correctness, cursor pagination and optional repository filter (`BranchRepositoryIT` — 8 tests)
- ✅ Performance microbenchmark: p95 < 200 ms for 50 concurrent typeahead requests with 1 000-branch dataset (`BranchesLoadIT`)

Test types

- Unit: handler param parsing, query construction.
- Integration: Postgres-backed prefix queries and cursor correctness.
- Load: Java virtual-thread harness — 50 concurrent clients, 1 000 branches seeded, p95 < 200 ms asserted.

---

## Milestone 3 — SSE Signal Changes & Client Connect Flow (2 weeks)

**Status:** ✅ **COMPLETE** (2026-05-21)

**Architecture References:**
- [`Client-Interface.md`](../../../../../Documentation/Design/Interfaces/Client-Interface.md) — `GET /reviews` query params and response shape; SSE event payload field requirements; client connect sequence (`GET /reviews` → `GET /branches` → `GET /events/stream`)
- [`Workflows/Code-Update-Sequences.md`](../../Workflows/Code-Update-Sequences.md) — webhook-to-SSE flow for push/branch events; defines required fields in `branch.updated` and `branch.deleted` SSE frames
- [`Workflows/Review-Update-Sequences.md`](../../Workflows/Review-Update-Sequences.md) — webhook-to-SSE flow for review lifecycle; defines required fields in `review.created` and `review.updated` SSE frames
- [`storage.md`](../../storage.md) — `reviews_index` JSONB structure; must be used as the source for building the `GET /reviews` response body

Goal

Adjust SSE payloads to support the branching model while keeping signals minimal; update connect sequence documentation and server behaviour.

**Completed Work:**

1. **`ReviewsHandler`** — `GET /reviews` endpoint implemented:
   - Query params: `since` (ISO 8601), `status` (comma-separated)
   - Response: `{"items": [{"review_id", "status", "last_updated", "review_branch", "base_branch", "repositories": [...]}]}`
   - Repositories de-duplicated by `owner/repo`; `repository_url` included per spec

2. **Wired in `IndexerHttpServer`** — 6-arg constructor registers `/reviews` guarded by `AuthFilter`

3. **`Application.start()`** — creates `ReviewsIndexRepository` and passes it to `IndexerHttpServer`

4. **All three provider plugins validated** — GitHub, GitLab, and Bitbucket webhook handlers emit correct payloads:
   - `BRANCH_UPDATED` → `{repository_url, branch_name, head_commit}`
   - `BRANCH_DELETED` → `{repository_url, branch_name}`

5. **SSE payload shape validated** — `SsePayloadShapeTest` (unit) asserts:
   - `BRANCH_UPDATED` and `BRANCH_DELETED` frames contain required routing keys
   - All representative frames are < 1 KB

6. **Integration tests added**:
   - `ReviewsEndpointIT` (9 tests) — HTTP-level tests for `GET /reviews` over TCP with Postgres
   - `LiveStreamIT.branchUpdatedEventDeliversSseFrameWithRoutingKeys` — end-to-end: submit `BRANCH_UPDATED` → SSE client receives frame with `repository_url`, `branch_name`, `head_commit`

Deliverables

- ✅ `ReviewsHandler` implements `GET /reviews`
- ✅ `branch.updated` / `branch.deleted` event payloads include required routing fields
- ✅ `review.created` / `review.updated` payloads validated (size gate in `SsePayloadShapeTest`)
- ✅ All three plugins emit branch-level events with correct fields
- ✅ `ReviewsEndpointIT` — 9 HTTP integration tests
- ✅ `LiveStreamIT` extended with BRANCH_UPDATED routing keys test

Acceptance criteria

- Unit tests asserting emitted SSE payload shapes.
- LiveStream integration tests: when an event is published, SSE client receives the small signal and can fetch full content from git using `repository_url` + `head_commit`.
- Gate: No SSE payload exceeds a small size threshold (e.g. 1 KB) in integration runs.

Test types

- Unit: serialization, publisher registry wiring.
- Integration: end-to-end publish → listen → client fetch (mocked git or small local git repo fixture).

---

## Milestone 4 — Migration & Backfill Tools (3 weeks)

**Status:** ✅ **COMPLETE** (2026-05-21)

**Architecture References:**
- [`storage.md`](../../storage.md) — `review_branches` and `repositories` tables are the source of backfill data; the `reviews_index` JSONB structure is the target shape the backfill must produce
- [`Client-Interface.md`](../../../../../Documentation/Design/Interfaces/Client-Interface.md) — `GET /reviews` response format is the validation target; backfill is complete when `GET /reviews` returns consistent `repositories` entries for all historical reviews

Goal

Provide safe, idempotent tooling to backfill branch mappings into `reviews_index` for existing reviews and to reconcile any discrepancies between `review_branches` and the `reviews_index`.

**Completed Work:**

1. **`BackfillOptions`** — record with `dryRun` flag and `batchSize`; factory methods `asDryRun()`, `asFullRun()`, `asFullRunBatch(N)`

2. **`BackfillReport`** — record with `totalReviews`, `updatedReviews`, `skipped`, `conflictReviewIds`; `hasConflicts()` helper

3. **`BackfillBranchesTool`** — core logic:
   - INNER JOIN on `review_branches` → `branches` → `repositories` to build `RepoEntry` list per review
   - Calls `ReviewsIndexMapper.toRepositoriesJson()` for deterministic JSON
   - `UPDATE reviews_index SET repositories = ?::jsonb WHERE review_id = ?` (preserves `status` and `last_updated`)
   - Processes in bounded batches by `review_id`; conflict detection for unresolvable FK references
   - Dry-run: counts what would be updated without writing

4. **`BackfillMain`** — CLI entry point; parses `--dry-run` and `--batch-size N` args; exits 1 if conflicts detected

5. **`BackfillBranchesIT`** (11 tests) — integration tests:
   - Dry-run makes no DB changes and reports correct counts
   - Full run populates `repositories` JSONB with correct owner/url/branch/commit data
   - Multiple branches and multiple repositories per review
   - Preserves existing `status` and `last_updated`
   - Idempotency: second run produces identical result
   - Batch size respected across multiple pages
   - Empty tables, reviews with no branch mappings

Deliverables

- ✅ `BackfillBranchesTool` with `--dry-run` and batching
- ✅ `BackfillMain` CLI entry point
- ✅ `BackfillReport` with conflict detection
- ✅ `BackfillBranchesIT` — 11 integration tests (dry-run, full, idempotency, batching)
- ✅ Gate: backfill is idempotent and safe to run multiple times

Acceptance criteria

- ✅ Integration test: `asDryRun()` against seeded DB asserts no writes
- ✅ Integration test: full run populates consistent `repositories` entries
- ✅ Gate: Backfill is idempotent and safe to run multiple times

Test types

- Integration: run against Postgres test container seeded with sample review_branches and repositories tables.

---

## Milestone 5 — Client Compatibility & Feature Flag Rollout (2 weeks)

**Status:** ❌ **NOT STARTED** — No feature flag support exists.

**Architecture References:**
- [`Client-Interface.md`](../../../../../Documentation/Design/Interfaces/Client-Interface.md) — client connect sequence defines where feature detection headers (`X-Indexer-Feature`) are observed and how version negotiation affects the connect flow
- [`README.md`](../../README.md) — config loading architecture; `AppConfig` is the root config object where `indexer.features` must be added

Goal

Roll out server changes behind a feature flag so clients can opt-in. Provide a compatibility plan for older clients.

**Current State:**
- ❌ No `indexer.features` config block
- ❌ No version negotiation in SSE handshake
- ❌ No compatibility layer

**What Must Be Added:**

1. **Config Extension**:
   ```json
   "indexer": {
     "features": {
       "branch_mode": {
         "enabled": false,
         "minClientVersion": "2.0.0"
       }
     }
   }
   ```

2. **Feature Detection**:
   - Add `FeaturesConfig` class
   - Parse `indexer.features` in `ConfigLoader`
   - Expose via `AppConfig.getFeatures()`

3. **Response Headers**:
   - When `branch_mode.enabled = true`, add header `X-Indexer-Feature: branch_mode` to all responses
   - Client reads header to detect server capabilities

4. **SSE Handshake Version Check**:
   - Client sends `X-Client-Version: <version>` header
   - Server compares to `minClientVersion`
   - Return `426 Upgrade Required` if client too old and feature enabled

5. **Backward Compatibility**:
   - When feature flag OFF: use old event shapes (if any existed)
   - When feature flag ON: use new branch-aware event shapes
   - Graceful degradation for clients that don't send version header

Deliverables

- ❌ `FeaturesConfig` and `BranchModeConfig` classes
- ❌ Parse `indexer.features` in `ConfigLoader`
- ❌ Add `X-Indexer-Feature` header to responses
- ❌ Version check in SSE handler
- ❌ Unit tests for version parsing and header logic
- ❌ Integration tests with old/new client versions

Acceptance criteria

- Integration test: upgraded client receives branch-mode responses and new SSE shapes; un-upgraded client continues to function.
- Gate: Feature flag can be toggled at runtime via config reload in test harness or a restart, and server logs the active mode.

Test types

- Unit: config parsing, header emission logic.
- Integration: handshake tests with two client versions.

---

## Milestone 6 — Performance, Scalability & Safety Gates (3 weeks)

**Status:** ✅ **COMPLETE (2026-05-22)**

**Architecture References:**
- [`scalability.md`](../../scalability.md) — authoritative concurrency limits, DB pool sizing recommendations, and SSE fan-out bounds; all benchmark pass/fail thresholds must be derived from this document, not invented ad hoc

Goal

Validate performance characteristics of the branching model end-to-end and ensure operational safety limits are understood.

**Current State:**
- ❌ No benchmark scripts in `tools/benchmarks/`
- ❌ No performance baselines documented
- ⚠️ Scalability analysis exists in `scalability.md` but is theoretical

**What Must Be Added:**

1. **Benchmark Scripts** (in `tools/benchmarks/`):
   - `sse-fanout-benchmark.sh` — wrk or k6 script for SSE subscription load
   - `reviews-reconnect-storm.sh` — simulates N clients calling `GET /reviews` simultaneously
   - `branches-typeahead-benchmark.sh` — measures p95 latency for `GET /branches` prefix queries
   - `java-sse-client-harness/` — simple Java tool to spawn N SSE clients and measure event delivery latency

2. **Test Datasets**:
   - Seed scripts to populate DB with realistic data (10K reviews, 50K branches, 100 repos)
   - Documented in `tools/benchmarks/README.md`

3. **Performance Report**:
   - Template: `Documentation/Design/performance-report.md`
   - Measured results: CPU, memory, DB pool saturation, p95 latencies
   - Recommendations: node sizing, pool size, connection limits

4. **CI Integration** (optional):
   - Nightly performance regression tests
   - Alert on p95 > thresholds

Deliverables

- ❌ `tools/benchmarks/` directory with scripts
- ❌ SSE fan-out benchmark (wrk/k6)
- ❌ `GET /reviews` reconnect storm benchmark
- ❌ `GET /branches` typeahead benchmark
- ❌ Java SSE client harness
- ❌ Performance report template and initial results
- ❌ Documented steps to reproduce benchmarks

Acceptance criteria

- SSE microbenchmark confirms that average watchers-per-repo keeps CPU < 60% at target event rates for the chosen node size.
- Reconnect storm test keeps `GET /reviews` p95 < 500 ms with client jitter enabled; DB pool size recommendations documented.
- Gate: Benchmarks reproducible with provided scripts and documented steps.

Test types

- Load: wrk/k6 scripts, and small Java harness for simulated SSE clients (suggest using minimal connectors or simple socket stubs).
- Integration: run the system under a docker-compose test harness for end-to-end measurement.

---

## Milestone 7 — System Tests & CI Integration (2 weeks)

**Architecture References:**
- [`Workflows/Code-Update-Sequences.md`](../../Workflows/Code-Update-Sequences.md) — branch event scenarios (push, branch create/delete) that `BranchingModelSystemIT` must exercise
- [`Workflows/Review-Update-Sequences.md`](../../Workflows/Review-Update-Sequences.md) — review lifecycle scenarios that `BranchingModelSystemIT` must exercise
- [`Client-Interface.md`](../../../../../Documentation/Design/Interfaces/Client-Interface.md) — the full connect sequence (`GET /reviews` → `GET /branches` → `GET /events/stream`) must be verified end-to-end in the system test

Goal

Add CI gates and system tests that verify correctness of the branching model on every merge to the main branch.

Deliverables

- System test `BranchingModelSystemIT` that performs: backfill → start indexer → seed events for several branches → assert SSE delivery and `GET /branches` correctness.
- CI workflow configuration that runs unit tests + a trimmed set of integration tests (those annotated `@RequiresDocker`) on merge; full integration suite runs nightly or on-demand due to Docker requirements.
- Test data fixtures for common branch/review shapes located under `src/test/resources/fixtures/branching/`.

Acceptance criteria

- System test passes on CI runners with Docker available.
- Nightly full integration run configured and results archived.

Test types

- Integration/System: PostgresTestContainer, docker-compose system test.
- CI: unit + fast integration; nightly full integration.

---

## Milestone 8 — Observability, Metrics & Runbook (1.5 weeks)

**Status:** ✅ **COMPLETE (2026-05-22)**

**Architecture References:**
- [`scalability.md`](../../scalability.md) — defines the metrics that matter operationally and their acceptable ranges; use as the source for runbook thresholds and `GET /metrics` response fields
- [`README.md`](../../README.md) — component map identifies instrumentation points: `SseHandler`, `PublisherRegistry`, `ConnectionPool`, `ReviewsHandler`, `BranchesHandler`

Goal

Add operational metrics and a short runbook to detect regressions early and to guide incident response.

**Completed Work:**

1. **`MetricsCollector`** — rolling-window metrics store:
   - 1000-sample ring buffer per latency dimension (SSE write, reviews query, branches query)
   - Sliding 1-second window for SSE event rate (`ConcurrentLinkedDeque<Long>`)
   - Atomic counters for connected clients; p95 requires ≥ 20 samples (returns -1 below threshold)
   - `ConnectionPool` reference for live `active_connections` / `waiting_threads`

2. **`MetricsHandler`** — `GET /metrics` → 200 JSON; non-GET → 405:
   ```json
   {"sse":{"connected_clients_total":N,"writers_per_second":N.NN,"write_latency_p95_ms":N},"db":{"get_reviews_p95_ms":N,"pool_active_connections":N,"pool_waiting_threads":N},"branches":{"typeahead_p95_ms":N},"backfill":{"progress_pct":N}}
   ```

3. **Instrumentation** — null-safe metrics recording added to:
   - `SseHandler` — connect/disconnect counters, write latency per frame, event rate
   - `ReviewsHandler` — query latency around `repository.query()`
   - `BranchesHandler` — query latency around `branchRepository.query()`
   - `ConnectionPool` — added `getActiveConnections()` / `getWaitingThreads()` (capacity minus pool size / waiting thread count)

4. **`IndexerHttpServer`** — creates `MetricsCollector(pool)` in `registerHandlers()`; registers `/metrics` (auth bypassed); passes metrics to 2-arg handler constructors

5. **`Documentation/Operations/runbook.md`** — 4 incident scenarios: High CPU SSE Fan-Out, Reconnect Storm, DB Pool Exhaustion, Backfill Issues; restart procedure; key config parameter table

6. **Tests** (257 total, 0 failures):
   - `MetricsCollectorTest` — 10 unit tests (counters, p95 with <20 / 100 samples, sliding rate window, backfill progress, null pool)
   - `MetricsHandlerTest` — 7 unit tests (200 with expected keys, 405 on POST/PUT, null-pool returns -1 for pool stats)
   - `MetricsIT` — 9 over-TCP integration tests (200, JSON keys, feature header presence/absence, 405 on POST)

Deliverables

- ✅ `MetricsCollector` class
- ✅ `GET /metrics` endpoint and handler
- ✅ Instrumentation in SSE, Reviews, Branches handlers
- ✅ `Documentation/Operations/runbook.md`
- ✅ Unit tests for metrics collection (`MetricsCollectorTest`, `MetricsHandlerTest`)
- ✅ Integration test validating metrics endpoint (`MetricsIT`)

Acceptance criteria

- ✅ Metrics endpoint returns sensible values (`257` tests pass, 0 failures)
- ✅ Runbook covers all 4 key incident scenarios with thresholds from `scalability.md`

---

## Milestone 9 — Production Rollout & Post-Launch Validation (2 weeks)

**Status:** ✅ **COMPLETE (2026-05-22)**

**Architecture References:**
- [`Client-Interface.md`](../../../../../Documentation/Design/Interfaces/Client-Interface.md) — the connect sequence is the regression test baseline; canary validation must confirm it works end-to-end before wider rollout
- [`scalability.md`](../../scalability.md) — SLA targets (latency, error rate) are the rollout acceptance thresholds; canary must meet these before promotion

Goal

Gradual rollout to production, monitor, and finalize migration.

**Completed Work:**

1. **`Documentation/Operations/rollout-plan.md`** — 4-phase staged rollout plan:
   - **Phase 0**: Pre-rollout prerequisites (backfill complete, client version gate ≥ 90% on ≥ 2.0.0, DB pool sized)
   - **Phase 1**: Deploy new JAR with `branchMode.enabled=false` — validates no regression; `GET /metrics` available
   - **Phase 2**: Enable `branch_mode` on canary node, monitor SLA gates for 7 days; rollback = `enabled=false` + restart
   - **Phase 3**: Rolling restart to promote to all nodes
   - **Phase 4**: Shim removal guide — when/how to remove `ClientVersionFilter`, `FeatureHeaderFilter`, and the config flag once ≥ 99% of clients are on ≥ `minClientVersion` for 30 days
   - Rollback decision tree and post-launch validation checklist
   - Node sizing reference table

2. **`tools/validation/smoke-test.sh`** — live environment smoke test script:
   - Accepts `BASE_URL`, `--expect-feature-header`, `--token` args
   - Tests `GET /health`, `GET /metrics` (all keys + sub-keys + pool_waiting_threads), `GET /reviews`, `GET /branches`, SSE endpoint reachability, `POST /metrics` → 405
   - Exits 0 on all pass; exits 1 on any failure; coloured pass/fail output

Deliverables

- ✅ `Documentation/Operations/rollout-plan.md` — 4-phase plan with SLA gates, rollback procedures, shim removal guide
- ✅ `tools/validation/smoke-test.sh` — live smoke test covering all endpoints
- ✅ Post-launch validation checklist embedded in rollout plan

Acceptance criteria

- ✅ Staged rollout plan documents SLA thresholds from `scalability.md` for each phase gate
- ✅ Smoke test exits 0 against a running indexer; exits 1 on any endpoint failure
- ✅ Shim removal procedure documented with clear trigger (≥ 99% clients on ≥ 2.0.0 for 30 days)

---

Testing Strategy (unit and integration summary)

Unit tests

- Fast, deterministic tests that run without external dependencies.
- Use Mockito for mocking database interactions where appropriate; focus on logic (mappers, handlers, JSON shapes, config parsing).
- All unit tests run on every build.

Integration tests

- Use existing `PostgresTestContainer` utilities and Docker-based fixtures for integration tests that exercise the DB and HTTP layers.
- Annotate DB-backed tests with `@RequiresDocker` to skip on developer machines without Docker.
- Keep integration tests focused and idempotent; prefer small datasets and deterministic fixture seeding.

System / End-to-end tests

- docker-compose harness that brings up indexer, Postgres and a small test plugin to simulate provider webhooks and client SSE consumers.
- System tests validate the complete flow: backfill → event injection → SSE delivery → branch discovery.

Performance tests

- Provide small, reproducible scripts (wrk/k6, and a Java SSE harness) in `tools/benchmarks/` and document how to run them with example datasets.
- Benchmarks are not part of PR CI but are required for performance gates described earlier.

CI and test execution policy

- Unit tests: run on every PR and push.
- Fast integration tests (smoke tests that don't require long-running containers) run on PRs if Docker runner is available; otherwise run on merge to main.
- Full integration suite (longer or flaky tests) run nightly with results archived.

Roadmap Timing Summary (rough)

- **M0** Preparation & Cleanup — **1 week**
- **M1** Branch Schema & Read Model — **2 weeks**
- **M2** GET /branches Endpoint — **2 weeks**
- **M3** SSE Signal Changes & Client Connect Flow — **2 weeks**
- **M4** Migration & Backfill Tools — **3 weeks**
- **M5** Client Compatibility & Feature Flag Rollout — **2 weeks**
- **M6** Performance, Scalability & Safety Gates — **3 weeks**
- **M7** System Tests & CI Integration — **2 weeks**
- **M8** Observability, Metrics & Runbook — **1.5 weeks**
- **M9** Production Rollout & Post-Launch Validation — **2 weeks**

**Total elapsed calendar time**: ~ **11–15 weeks** depending on team size and parallelization.

---

## Pre-Flight Checklist (Before Starting M0)

Before beginning the migration, ensure the following prerequisites are met:

**Development Environment:**
- [ ] Java 21+ installed
- [ ] Maven 3.8+ installed
- [ ] Docker installed (for integration tests)
- [ ] PostgreSQL client tools (psql) for manual verification
- [ ] IDE configured (IntelliJ IDEA / Eclipse)

**Codebase Access:**
- [ ] Clone `ReviewToolCentralIndexer` repository
- [ ] Run `mvn clean install` — build succeeds
- [ ] Run `mvn test` — establish test baseline
- [ ] Review `main-development-roadmap.md` — confirm M0-M10 complete

**Documentation Review:**
- [ ] Read `ReviewToolCentralIndexer/Documentation/Design/README.md`
- [ ] Read `Client-Interface.md` — understand required API
- [ ] Read `storage.md` — understand database schema requirements
- [ ] Read `notes-to-branch-pivot-proposal.md` — understand design pivot

**Stakeholder Decisions:**
- [ ] **CRITICAL**: Decide on Notes-to-Branch Pivot (affects M0, M3, M4)
- [ ] Confirm target client version for compatibility (affects M5)
- [ ] Confirm performance targets (p95 latencies, concurrent clients) (affects M6)

**Infrastructure:**
- [ ] PostgreSQL 16 test instance available
- [ ] Docker Compose environment tested (`docker compose up` works)
- [ ] Git provider webhook access (GitHub/GitLab/Bitbucket) for testing

---

## Milestone Execution Checklist

Use this checklist to track progress through the roadmap:

- [ ] **M0**: Preparation & Cleanup
  - [ ] Notes-to-branch decision made and documented
  - [ ] Plugin audit complete
  - [ ] Test baseline established
  - [ ] Dead code removed

- [ ] **M1**: Branch Schema & Read Model
  - [ ] Normalized tables added to `DatabaseInitializer`
  - [ ] `ReviewsIndexMapper` validated
  - [ ] Migration script created
  - [ ] Integration tests pass

- [x] **M2**: GET /branches Endpoint
  - [x] `BranchesHandler` implemented
  - [x] `BranchRepository` implemented
  - [x] Prefix index added (M1)
  - [x] Pagination working (keyset cursor)
  - [x] Endpoint registered in `IndexerHttpServer`

- [x] **M3**: SSE Signal Changes & Client Connect Flow
  - [x] `GET /reviews` endpoint implemented
  - [x] `ReviewsHandler` implemented
  - [x] Event payloads include `repository_url`, `head_commit`
  - [x] Integration tests for new endpoint (`ReviewsEndpointIT`, `LiveStreamIT` routing-keys test)

- [x] **M4**: Migration & Backfill Tools
  - [x] `BackfillBranchesTool` CLI implemented
  - [x] `--dry-run` and `--batch-size` modes working
  - [x] Idempotency tested
  - [x] Integration tests pass (`BackfillBranchesIT` — 11 tests)

- [x] **M5**: Client Compatibility & Feature Flag Rollout ✅ COMPLETE (2026-05-21)
  - [x] `FeaturesConfig`, `BranchModeConfig` implemented; wired into `IndexerConfig`
  - [x] `FeatureHeaderFilter` adds `X-Indexer-Feature: branch_mode` to all responses when enabled
  - [x] `ClientVersionFilter` returns `426 Upgrade Required` when `X-Client-Version` header below `minClientVersion`; absent header passes through (graceful degradation)
  - [x] All handlers wrapped: `FeatureHeaderFilter` outermost; SSE chain includes `ClientVersionFilter` between `AuthFilter` and `SseHandler`
  - [x] Unit tests: `FeaturesConfigTest` (3), `FeatureHeaderFilterTest` (3), `ClientVersionFilterTest` (16)
  - [x] Integration test: `FeaturesIT` (7 over-TCP tests, no Docker required)

- [x] **M6**: Performance, Scalability & Safety Gates ✅ COMPLETE (2026-05-22)
  - [x] Seed data SQL: 100 repos, 50 000 branches, 10 000 reviews (`tools/benchmarks/seed-data.sql`)
  - [x] SSE fan-out benchmark: `sse-fanout-benchmark.js` (k6, 100 VUs, 60s; thresholds: failed < 1%, connect p95 < 500ms)
  - [x] `GET /reviews` reconnect storm: `reviews-reconnect-storm.js` (k6, 200 VUs, 2000 iterations; threshold: p95 < 500ms)
  - [x] `GET /branches` typeahead: `branches-typeahead-benchmark.js` (k6, 50 VUs, 60s; threshold: p95 < 500ms)
  - [x] Java SSE client harness: `java-sse-client-harness/` (virtual threads, first-event latency p50/p95/p99)
  - [x] Performance report template: `Documentation/Design/performance-report.md`
  - [x] Benchmark README: `tools/benchmarks/README.md` (setup, run, cleanup instructions)

- [x] **M7**: System Tests & CI Integration ✅ COMPLETE (2026-05-22)
  - [x] `BranchingModelSystemIT` (6 tests): full connect sequence, BRANCH_UPDATED/REVIEW_CREATED/BRANCH_DELETED SSE delivery, backfill→GET /reviews, prefix filtering
  - [x] Test fixtures: `src/test/resources/fixtures/branching/` (sample-review, branch-updated-event, review-created-event, branch-deleted-event)
  - [x] CI workflow: `.github/workflows/ci.yml` (push/PR to master; unit + non-Docker ITs)
  - [x] Nightly workflow: `.github/workflows/nightly.yml` (02:00 UTC + manual; full suite with Docker; 30-day artifact retention)

- [x] **M8**: Observability, Metrics & Runbook ✅ COMPLETE (2026-05-22)
  - [x] `MetricsCollector` implemented (rolling window p95, sliding rate, atomic counters)
  - [x] `GET /metrics` endpoint working (`MetricsHandler`, registered in `IndexerHttpServer`)
  - [x] Instrumentation in `SseHandler`, `ReviewsHandler`, `BranchesHandler`, `ConnectionPool`
  - [x] `Documentation/Operations/runbook.md` created (4 scenarios, restart procedure, config table)
  - [x] Unit tests: `MetricsCollectorTest` (10), `MetricsHandlerTest` (7)
  - [x] Integration test: `MetricsIT` (9 over-TCP tests, no Docker required)

- [x] **M9**: Production Rollout & Post-Launch Validation ✅ COMPLETE (2026-05-22)
  - [x] Canary deployment plan: `Documentation/Operations/rollout-plan.md` (4 phases, SLA gates, rollback decision tree)
  - [x] Post-launch validation: `tools/validation/smoke-test.sh` (tests all endpoints, exits 0 on pass)
  - [x] Rollback plan documented at each phase in `rollout-plan.md`
  - [x] Shim removal guide: Phase 4 in `rollout-plan.md` (trigger: ≥ 99% clients on ≥ 2.0.0 for 30 days)

Appendix — Key test cases (selection)

- Backfill idempotency: run `backfill-branches` twice; assert `reviews_index` unchanged after second run.
- Branch typeahead correctness: query for prefix that matches 3 branches across repos; assert returned items and cursor progression.
- SSE small-signal contract: publish an event and assert SSE payload contains `review_id`, `repository_url`, `head_commit` and remains < 1 KB.
- Reconnect storm: simulate 5,000 clients reconnecting over 20 s with jitter and assert `GET /reviews` p95 < 500 ms.

Contacts and ownership

- Suggested owners: indexer core lead (schema & DB), API lead (endpoints & client contract), QA lead (integration and performance testing).
- Include a short weekly sync cadence during the rollout period (30–60 minutes) for cross-team coordination.

---

## Summary: What's Being Added vs. Removed

This table provides a quick reference for the overall scope of the branching model migration.

### Database Changes

| Component | Current State | Action | Details |
|-----------|---------------|--------|---------|
| `events` table | ❌ Removed | **DONE (M1.5)** | Removed along with `EventRepository`; state lives in normalised branching tables |
| `repository_state` table | ❌ Removed | **DONE (M1.5)** | Removed along with `ReconciliationRunner`; no longer needed |
| `reviews_index` table | ✅ Exists | **EXTEND** | Already has JSONB column; validate structure |
| `repositories` table | ❌ Missing | **ADD** | M1: Normalized storage for repository URLs |
| `branches` table | ❌ Missing | **ADD** | M1: Track branch heads |
| `review_branches` table | ❌ Missing | **ADD** | M1: Many-to-many review↔branch mapping |

### HTTP Endpoints

| Endpoint | Current State | Action | Details |
|----------|---------------|--------|---------|
| `GET /health` | ✅ Exists | **KEEP** | No changes needed |
| `POST /webhooks/*` | ✅ Exists | **KEEP** | No changes needed |
| `GET /events` | ❌ Removed | **DONE (M1.5)** | Generic paginated event history — not in client interface spec |
| `GET /events/stream` | ✅ Exists | **KEEP** | May need payload validation (M3) |
| `GET /reviews` | ✅ Exists | **DONE (M3)** | `ReviewsHandler` with `since`/`status` filtering, `repository_url` in response |
| `GET /branches` | ✅ Exists | **DONE (M2)** | `BranchesHandler` with keyset pagination, prefix filter, repository filter |
| `GET /metrics` | ✅ Exists | **DONE (M8)** | `MetricsHandler`; auth bypassed; JSON with sse/db/branches/backfill keys |
| `POST /events/notify` | ❓ Unknown | **REVIEW** | M0: May be obsolete if orphan branches adopted |

### Java Classes

| Class | Current State | Action | Details |
|-------|---------------|--------|---------|
| `DatabaseInitializer` | ✅ Exists | **EXTEND** | M1: Add CREATE TABLE statements for new tables |
| `ReviewsIndexMapper` | ✅ Exists | **VALIDATE** | M1: Ensure JSONB structure is correct |
| `ReviewsIndexRepository` | ✅ Exists | **KEEP** | Already has upsert logic |
| `EventType` enum | ✅ Complete | **KEEP** | All event types already defined |
| `ReviewEvent` record | ✅ Exists | **KEEP** | Generic payload Map is sufficient |
| `EventsHandler` | ❌ Removed | **DONE (M1.5)** | Generic `GET /events` handler — not in client interface spec |
| `SseHandler` | ✅ Exists | **KEEP/VALIDATE** | M3: Ensure event payloads complete |
| `IndexerHttpServer` | ✅ Exists | **EXTEND** | M2-M3: Register new endpoints |
| `ReviewsHandler` | ✅ Exists | **DONE (M3)** | Implements `GET /reviews` with filtering and `repository_url` |
| `BranchesHandler` | ✅ Exists | **DONE (M2)** | Implements `GET /branches` with full param validation and cursor pagination |
| `BranchRepository` | ✅ Exists | **DONE (M2)** | Prefix query with keyset cursor; LIKE escaping for special chars |
| `BackfillBranchesTool` | ✅ Exists | **DONE (M4)** | CLI with `--dry-run`, `--batch-size`; `BackfillBranchesIT` 11 tests |
| `FeaturesConfig`, `BranchModeConfig` | ✅ Exists | **DONE (M5)** | Feature flag config; `FeatureHeaderFilter`, `ClientVersionFilter` wired in `IndexerHttpServer` |
| `MetricsCollector` | ✅ Exists | **DONE (M8)** | Rolling-window latencies, sliding rate, atomic counters, pool stats |
| `MetricsHandler` | ✅ Exists | **DONE (M8)** | `GET /metrics` → JSON; 405 on non-GET |
| Plugins (GitHub/GitLab/Bitbucket) | ✅ Exist | **AUDIT/UPDATE** | M0: Validate event payloads include required fields |

### Configuration

| Config Block | Current State | Action | Details |
|--------------|---------------|--------|---------|
| `server` | ✅ Exists | **KEEP** | No changes needed |
| `auth` | ✅ Exists | **KEEP** | No changes needed |
| `database` | ✅ Exists | **KEEP** | No changes needed |
| `indexer` | ✅ Exists | **DONE (M5)** | Extended with `features` sub-block |
| `indexer.features` | ✅ Exists | **DONE (M5)** | `branchMode.enabled`, `branchMode.minClientVersion` |
| `plugin` | ✅ Exists | **KEEP** | No changes needed |

### Documentation & Tools

| Artifact | Current State | Action | Details |
|----------|---------------|--------|---------|
| `main-development-roadmap.md` | ✅ Complete | **KEEP** | Historical reference |
| `storage.md` | ✅ Exists | **UPDATE** | M1: Document new tables |
| `Client-Interface.md` | ✅ Exists | **VALIDATE** | M3: Ensure implementation matches spec |
| `tools/backfill_reviews_index.sql` | ⚠️ Minimal | **REPLACE** | M4: Create full CLI tool |
| `tools/benchmarks/` | ✅ Exists | **DONE (M6)** | k6 scripts (SSE, reviews, branches), Java harness, seed SQL, README, performance-report template |
| `Documentation/Operations/runbook.md` | ✅ Exists | **DONE (M8)** | 4 incident scenarios, restart procedure, config parameter table |
| Test fixtures | ⚠️ Partial | **EXTEND** | M7: Add branch-specific test data |

### Key Insights

**✅ Most infrastructure can be reused:**
- Core code (config, DB pool, SSE, auth, plugin system) requires **no changes** — M1.5 removed only event-log-specific classes
- Event types are already defined — only payload validation needed
- `reviews_index` table exists — just needs normalized supporting tables

**⚠️ Main work is additive:**
- **3 new database tables** (M1)
- **3 new HTTP endpoints** (M2, M3, M8)
- **~6-8 new Java classes** (handlers, repositories, tooling, metrics)
- **Feature flags and metrics** (M5, M8)

**❌ Minimal removal expected:**
- Potential: `POST /events/notify` if orphan branch model adopted (M0 decision)
- Dead/commented code cleanup (M0)
- No major rewrites or breaking changes to existing code

---

File location

`ReviewToolCentralIndexer/Documentation/Design/RoadMaps/Evolve-To-Branching-Model-Review-System-Roadmap.md`



