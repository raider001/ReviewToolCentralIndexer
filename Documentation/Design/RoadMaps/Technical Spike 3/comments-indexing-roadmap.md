# Comments Indexing — Roadmap (Technical Spike 3)

## Status Summary

- ⬜ **M0** — Schema Migration: Surrogate Key for `repositories`
- ⬜ **M1** — DB Schema: `comments_index` table
- ⬜ **M2** — Comment Change Detection on `kalynx-reviews` push
- ⬜ **M3** — `GET /reviews/{reviewId}/comments` Endpoint
- ⬜ **M4** — `comment.added` / `comment.updated` SSE Events
- ⬜ **M5** — System Tests & Validation

---

## Key Architecture Documents

| Document | Location | Relevant To |
|---|---|---|
| **Data Structure Design** | [`ServerlessReviewTool/Documentation/Design/datastructure.md`](../../../../../../Documentation/Design/datastructure.md) | All milestones — comment storage format (metadata/text/status streams per `comment_id`) |
| **Comment Design** | [`ServerlessReviewTool/Documentation/Design/comments.md`](../../../../../../Documentation/Design/comments.md) | All milestones — write/read paths, indexer detection rationale |
| **Client Interface Specification** | [`ServerlessReviewTool/Documentation/Design/Interfaces/Client-Interface.md`](../../../../../../Documentation/Design/Interfaces/Client-Interface.md) | M3, M4 — endpoint shape, SSE event payloads |
| **Database Storage Design** | [`storage.md`](../../storage.md) | M0, M1 — authoritative schema; all DDL must match exactly |
| **Event Overview** | [`Workflows/event-overview.md`](../../Workflows/event-overview.md) | M2, M4 — trigger conditions and SSE event mapping for comment events |
| **Comment Update Sequences** | [`Workflows/Comment-Update-Sequences.md`](../../Workflows/Comment-Update-Sequences.md) | M2, M4 — full webhook→SSE sequence diagrams |
| **Spike 2 Roadmap (completed)** | [`Technical Spike 2/Evolve-To-Branching-Model-Review-System-Roadmap.md`](../Technical%20Spike%202/Evolve-To-Branching-Model-Review-System-Roadmap.md) | M0 — current as-built DDL for `repositories`, `branches`, `review_branches` |

---

## Purpose

This spike adds comment routing support to the Central Indexer. Before any comment work can begin, the existing `repositories`, `branches`, and `review_branches` tables must be migrated from their composite-key design (implemented in Spike 2) to a surrogate UUID key design. This unblocks clean FK references throughout and eliminates the ambiguity of `repository` as a non-unique field.

---

## Milestone 0 — Schema Migration: Surrogate Key for `repositories`

**Goal:** Migrate the existing `repositories`, `branches`, and `review_branches` tables from the composite `(owner, repository)` key design to a surrogate `repository_id UUID` primary key. After this milestone, all FK references to a repository use a single stable UUID column.

### Architecture References
- [`storage.md`](../../storage.md) — authoritative post-migration schema; DDL must match exactly after migration

### Why Now

Spike 2 implemented the tables with this DDL:

```sql
-- CURRENT (pre-migration)
CREATE TABLE repositories (
    owner TEXT NOT NULL,
    repository TEXT NOT NULL,
    url TEXT NOT NULL,
    PRIMARY KEY (owner, repository)
);

CREATE TABLE branches (
    owner TEXT NOT NULL,
    repository TEXT NOT NULL,
    branch_name TEXT NOT NULL,
    head_commit TEXT NOT NULL,
    PRIMARY KEY (owner, repository, branch_name),
    FOREIGN KEY (owner, repository) REFERENCES repositories(owner, repository)
);

CREATE TABLE review_branches (
    review_id TEXT NOT NULL,
    owner TEXT NOT NULL,
    repository TEXT NOT NULL,
    branch_name TEXT NOT NULL,
    PRIMARY KEY (review_id, owner, repository, branch_name),
    FOREIGN KEY (review_id) REFERENCES reviews_index(review_id),
    FOREIGN KEY (owner, repository, branch_name) REFERENCES branches(owner, repository, branch_name)
);
```

Problems with this design:
- `repository` alone is not unique — two different owners can have the same repository name. The composite key relies on carrying `owner` everywhere.
- All FK references require two columns (`owner` + `repository`), which is verbose and fragile.
- A repository URL change (rename, org rename, host migration) would require cascading updates across all FK columns in all tables.
- `comments_index` would need to carry `owner` + `repository` as part of its key, which compounds these problems.

The surrogate `repository_id UUID` solves all three: uniqueness guaranteed, single-column FK, URL changes require updating only one row in `repositories`.

### Target DDL (post-migration)

```sql
-- repositories: add surrogate PK, add UNIQUE on url, keep owner/repository as metadata
ALTER TABLE repositories
    ADD COLUMN repository_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE repositories
    ADD CONSTRAINT repositories_pk PRIMARY KEY (repository_id);

ALTER TABLE repositories
    DROP CONSTRAINT repositories_pkey;   -- drops old (owner, repository) PK

ALTER TABLE repositories
    ADD CONSTRAINT repositories_url_unique UNIQUE (url);

-- branches: add repository_id FK, drop composite FK and redundant columns
ALTER TABLE branches
    ADD COLUMN repository_id UUID;

UPDATE branches b
    SET repository_id = r.repository_id
    FROM repositories r
    WHERE b.owner = r.owner AND b.repository = r.repository;

ALTER TABLE branches
    ALTER COLUMN repository_id SET NOT NULL;

ALTER TABLE branches
    DROP CONSTRAINT branches_pkey;

ALTER TABLE branches
    ADD PRIMARY KEY (repository_id, branch_name);

ALTER TABLE branches
    DROP CONSTRAINT branches_owner_repository_fkey;

ALTER TABLE branches
    ADD CONSTRAINT fk_branches_repository
        FOREIGN KEY (repository_id) REFERENCES repositories(repository_id);

ALTER TABLE branches
    DROP COLUMN owner,
    DROP COLUMN repository;

-- review_branches: same pattern
ALTER TABLE review_branches
    ADD COLUMN repository_id UUID;

UPDATE review_branches rb
    SET repository_id = r.repository_id
    FROM repositories r
    WHERE rb.owner = r.owner AND rb.repository = r.repository;

ALTER TABLE review_branches
    ALTER COLUMN repository_id SET NOT NULL;

ALTER TABLE review_branches
    DROP CONSTRAINT review_branches_pkey;

ALTER TABLE review_branches
    ADD PRIMARY KEY (review_id, repository_id, branch_name);

ALTER TABLE review_branches
    DROP CONSTRAINT review_branches_owner_repository_branch_name_fkey;

ALTER TABLE review_branches
    ADD CONSTRAINT fk_review_branches_repository
        FOREIGN KEY (repository_id) REFERENCES repositories(repository_id);

ALTER TABLE review_branches
    DROP COLUMN owner,
    DROP COLUMN repository;
```

The migration is executed in `DatabaseInitializer` as idempotent `ALTER TABLE … IF NOT EXISTS` / `IF EXISTS` guards, so it is safe to run on a fresh schema or against an existing database.

### What Must Also Change

- **`RepositoryRepository`** (or equivalent) — insert/lookup by `repository_id` not `(owner, repository)`
- **`BranchRepository`** — query by `repository_id`; cursor encoding changes from `owner\0repository\0branchName` to `repository_id\0branchName`
- **`BackfillBranchesTool`** — join via `repository_id`
- **`ReviewsIndexMapper`** — look up `repository_id` when building JSONB; `owner`/`repository`/`url` still included in the denormalized JSON for human-readable output
- All webhook handlers that currently insert into `repositories` or look up by `(owner, repository)` must switch to upsert-by-`url` and return/use `repository_id`

### Deliverables

- ⬜ Migration DDL in `DatabaseInitializer` — idempotent, runs on fresh and existing schemas
- ⬜ `RepositoryRepository` updated to insert/lookup via `repository_id`
- ⬜ `BranchRepository` updated — queries by `repository_id`; cursor updated
- ⬜ `BackfillBranchesTool` updated — join via `repository_id`
- ⬜ `ReviewsIndexMapper` updated — uses `repository_id` internally; JSON output unchanged
- ⬜ All webhook handlers updated

### Acceptance Criteria

- `DatabaseInitializerIT` asserts `repositories` has `repository_id UUID PK`, `url UNIQUE`; `branches` has `repository_id FK`; `review_branches` has `repository_id FK`
- Running `DatabaseInitializer` twice is idempotent on both a fresh DB and a DB already migrated
- All existing Spike 2 tests pass after the migration (no regressions in `BranchesHandler`, `ReviewsHandler`, `BackfillBranchesTool`)

### Test Types

- Integration: `PostgresTestContainer` — assert column presence, PK, FK, and UNIQUE constraints in `information_schema`
- Integration: run full Spike 2 test suite against migrated schema

---

## Milestone 1 — DB Schema: `comments_index` Table

**Goal:** Add the `comments_index` table to `DatabaseInitializer` so the indexer can record which comment threads have activity for a given review, keyed by `comment_id`.

### Architecture References
- [`storage.md`](../../storage.md) — authoritative schema; DDL must match exactly

### What Must Be Added

```sql
CREATE TABLE IF NOT EXISTS comments_index (
    comment_id    UUID        PRIMARY KEY,
    review_id     TEXT        NOT NULL REFERENCES reviews(review_id),
    repository_id UUID        NOT NULL REFERENCES repositories(repository_id),
    last_updated  TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_comments_index_review_id
    ON comments_index (review_id);
```

`comment_id` is the UUID v7 generated by the client at comment creation time — the same identifier used in the git storage path `reviews/{reviewId}/comments/{commentId}/`.

### Deliverables

- ⬜ `comments_index` table added to `DatabaseInitializer`
- ⬜ `storage.md` ERD verified — `DatabaseInitializer` must match exactly

### Acceptance Criteria

- `DatabaseInitializerIT` asserts `comments_index` exists with `comment_id UUID PK`, `review_id FK`, `repository_id FK`, and index on `review_id`
- Running `DatabaseInitializer` twice is idempotent

### Test Types

- Integration: `PostgresTestContainer` — assert table, PK, FKs, and index in `information_schema`

---

## Milestone 2 — Comment Change Detection on `kalynx-reviews` Push

**Goal:** When the indexer processes a push to `refs/heads/kalynx-reviews`, detect which `reviews/{reviewId}/comments/{commentId}/*` paths changed, classify the event type by which sub-stream was written, upsert `comments_index`, and fire one SSE event per changed comment.

### Architecture References
- [`Workflows/Comment-Update-Sequences.md`](../../Workflows/Comment-Update-Sequences.md) — full sequence
- [`Workflows/event-overview.md`](../../Workflows/event-overview.md) — sub-stream → event type mapping
- [`ServerlessReviewTool/Documentation/Design/datastructure.md`](../../../../../../Documentation/Design/datastructure.md) — comment storage structure: `metadata`/`text`/`status` sub-streams per `comment_id`

### Detection Logic

Each comment thread has three sub-streams at `reviews/{reviewId}/comments/{commentId}/`:

| Sub-stream changed | Event type | Meaning |
|---|---|---|
| `metadata` | `comment.added` | New comment created (metadata written once at creation) |
| `text` | `comment.added` | Comment text or reply appended |
| `status` | `comment.updated` | Resolution state changed |

When `kalynxReviewsUpdateCallback` fires for a given repository:

1. Run `git diff-tree --name-only -r <parentCommit> <newCommit>` to list changed paths
2. Filter paths matching `reviews/{reviewId}/comments/{commentId}/(metadata|text|status)`
3. For each distinct `reviewId` found — check `reviews` table; skip if `CLOSED`
4. For each changed `commentId` per open review:
   - Extract `reviewId` and `commentId` from the path
   - Determine event type from the sub-stream name: `metadata` or `text` → `comment.added`; `status` → `comment.updated`
   - If the same `commentId` has both `metadata`/`text` and `status` changes in one commit, emit `comment.added` (creation takes precedence)
   - Look up `repository_id` from `repositories` by the pushed repository's URL
   - `UPSERT comments_index (comment_id, review_id, repository_id, last_updated)`
5. Fire one SSE event per changed comment carrying `{review_id, repository_url, comment_id}` — `repository_url` resolved from `repositories` for the payload; `repository_id` stays internal

### New Classes

| Class | Responsibility |
|---|---|
| `CommentChangeDetector` | Diffs commit tree, extracts `(reviewId, commentId, subStream)` tuples from changed paths, returns `Map<commentId, CommentEventType>` per reviewId |
| `CommentEventType` | Enum: `ADDED`, `UPDATED` |
| `CommentsIndexRepository` | `upsert(commentId, reviewId, repositoryId, lastUpdated)`; `findByReviewId(reviewId)` — joins `repositories` to return `url` in results |

### Deliverables

- ⬜ `CommentChangeDetector` — path parsing + sub-stream classification
- ⬜ `CommentsIndexRepository` — upsert and query
- ⬜ Wired into `kalynxReviewsUpdateCallback` in `Application`

### Acceptance Criteria

- Unit: `CommentChangeDetector` classifies `metadata` or `text` path change as `ADDED` and `status` path change as `UPDATED`
- Unit: closed review guard — skips processing for `CLOSED` reviews
- Integration: push writing `metadata` sub-stream → `comments_index` row upserted with correct `comment_id`, `repository_id`, and `last_updated`
- Integration: push writing `status` sub-stream → existing row `last_updated` advances; SSE type is `comment.updated`

### Test Types

- Unit: mock git diff output; verify classification logic for each sub-stream type
- Integration: `PostgresTestContainer` + blobless clone fixture; assert `comments_index` row after push

---

## Milestone 3 — `GET /reviews/{reviewId}/comments` Endpoint

**Goal:** Implement the comments endpoint. Returns all `comments_index` rows for a review — one entry per comment thread. The client uses this on initial load to know exactly which `comment_id`s to fetch per repository.

### Architecture References
- [`ServerlessReviewTool/Documentation/Design/Interfaces/Client-Interface.md`](../../../../../../Documentation/Design/Interfaces/Client-Interface.md) — endpoint contract and response shape; auth required

### What Must Be Added

| Class | Responsibility |
|---|---|
| `CommentsHandler` | `GET /reviews/{reviewId}/comments` → 200 array or 404 if no rows |

Response (200):
```json
[
  {"repository_url": "https://github.com/alice/repo-a", "comment_id": "01890a5f-g1h2-774b-bcce-h4i5j6k78901", "last_updated": "2026-05-25T10:10:00.000Z"},
  {"repository_url": "https://github.com/alice/repo-a", "comment_id": "01890a5f-h2i3-774b-bcce-i5j6k7l89012", "last_updated": "2026-05-25T09:05:00.000Z"},
  {"repository_url": "https://github.com/alice/repo-b", "comment_id": "01890a5f-i3j4-774b-bcce-j6k7l8m90123", "last_updated": "2026-05-25T09:00:00.000Z"}
]
```

`repository_url` is resolved from `repositories` via JOIN on `repository_id`; the surrogate key does not appear in the response.

Response (404): review exists but has no comments yet.

Register in `IndexerHttpServer` behind `AuthFilter`. Wire `CommentsIndexRepository` through `Application`.

### Deliverables

- ⬜ `CommentsHandler` class
- ⬜ Registered in `IndexerHttpServer`
- ⬜ Wired in `Application`

### Acceptance Criteria

- Unit: returns 200 array when `CommentsIndexRepository.findByReviewId` returns rows; `repository_url` populated from JOIN
- Unit: returns 404 when no rows exist
- Unit: non-GET method returns 405
- Integration: over-TCP `GET /reviews/{id}/comments` returns 200 after M2 upsert; 401 without token; 404 for unknown review

### Test Types

- Unit: mocked repository; verify JSON array shape and status codes
- Integration: `PostgresTestContainer` over TCP; seed two rows for the same review across two repositories; assert both appear

---

## Milestone 4 — `comment.added` / `comment.updated` SSE Events

**Goal:** Fire the correct SSE event to connected clients after the `comments_index` upsert in M2.

### Architecture References
- [`Workflows/Comment-Update-Sequences.md`](../../Workflows/Comment-Update-Sequences.md) — SSE fires in parallel with the DB upsert
- [`Workflows/event-overview.md`](../../Workflows/event-overview.md) — payload: `{type, review_id, repository_url, comment_id}`

### What Must Be Added

After `CommentsIndexRepository.upsert()` completes, call `PublisherRegistry.publish()` with:

```
event: comment.added   (or comment.updated)
data: {"type":"comment.added","review_id":"...","repository_url":"...","comment_id":"..."}
```

`EventType` enum already contains `REVIEW_COMMENT_ADDED` and `REVIEW_COMMENT_UPDATED` — map these to the SSE event names `comment.added` and `comment.updated` in `SseHandler`.

### Deliverables

- ⬜ `comment.added` and `comment.updated` wired to `PublisherRegistry.publish()` from the callback path
- ⬜ `SseHandler` serialises the new event types with correct `event:` names and `comment_id` in payload
- ⬜ Payload size confirmed < 1 KB in `SsePayloadShapeTest`

### Acceptance Criteria

- Unit: `SseHandler` emits `event: comment.added` frame with `review_id`, `repository_url`, `comment_id`
- Integration (`LiveStreamIT`): trigger detection from M2 → SSE client receives `comment.added` frame within 2 seconds
- Gate: payload < 1 KB asserted in `SsePayloadShapeTest`

### Test Types

- Unit: frame format and payload shape
- Integration: end-to-end publish → SSE client receives frame

---

## Milestone 5 — System Tests & Validation

**Goal:** Verify the full end-to-end flow from a `kalynx-reviews` push to SSE delivery and routing endpoint response.

### Architecture References
- [`Workflows/Comment-Update-Sequences.md`](../../Workflows/Comment-Update-Sequences.md) — reference for the sequence to exercise in system tests

### Scenarios to Exercise

| Scenario | Expected outcome |
|---|---|
| Push writing `metadata` sub-stream for new `comment_id` | `comment.added` SSE with `comment_id`; `comments_index` row upserted; `GET /reviews/{id}/comments` returns entry |
| Push writing `text` sub-stream (reply) | `comment.added` SSE; `last_updated` advances |
| Push writing `status` sub-stream on existing comment | `comment.updated` SSE; `last_updated` advances |
| Push on a CLOSED review | No SSE; `comments_index` not updated |
| Push with changes across multiple `comment_id`s in one commit | One SSE event per changed comment |
| Push across two repositories for same review | Two separate SSE events; two separate rows in `comments_index` |
| `GET /reviews/{id}/comments` before any comments pushed | 404 |
| URL change in `repositories` (update `url`) | `comments_index` rows unaffected; next SSE resolves new URL via JOIN |

### Deliverables

- ⬜ `CommentIndexingSystemIT` — full end-to-end system test covering all scenarios above
- ⬜ `smoke-test.sh` extended to call `GET /reviews/{id}/comments` and assert 200 or 404 as appropriate

### Acceptance Criteria

- All scenarios pass against a live indexer with `PostgresTestContainer` and blobless clone fixture
- URL-change scenario confirms `comments_index` is decoupled from the URL string

### Test Types

- System/Integration: `PostgresTestContainer` + git fixture; simulate webhook push; assert DB + SSE + HTTP

---

## Roadmap Timing Summary

| Milestone | Estimated effort |
|---|---|
| M0 — Schema Migration | 2 days |
| M1 — `comments_index` DDL | 0.5 days |
| M2 — Comment Change Detection | 2.5 days |
| M3 — Routing Endpoint | 1 day |
| M4 — SSE Events | 1 day |
| M5 — System Tests | 2 days |
| **Total** | **~9 days** |

---

## Summary: What's Being Added or Changed

### Database

| Table | Action | Detail |
|---|---|---|
| `repositories` | Migrate | Add `repository_id UUID PK`; add `UNIQUE(url)`; drop `(owner, repository)` PK; keep `owner`/`repository` as metadata |
| `branches` | Migrate | Replace `(owner, repository)` composite FK with `repository_id FK`; drop `owner`/`repository` columns |
| `review_branches` | Migrate | Replace `(owner, repository)` composite FK with `repository_id FK`; drop `owner`/`repository` columns |
| `comments_index` | New | `comment_id UUID PK`, `review_id FK`, `repository_id FK`, `last_updated` |

### HTTP Endpoints

| Endpoint | Action |
|---|---|
| `GET /reviews/{reviewId}/comments` | New — returns `[{repository_url, comment_id, last_updated}]` array |

### Java Classes

| Class | Action |
|---|---|
| `DatabaseInitializer` | Extend — migration DDL for M0; `comments_index` DDL for M1 |
| `RepositoryRepository` | Update — insert/lookup via `repository_id` |
| `BranchRepository` | Update — query by `repository_id`; cursor updated |
| `BackfillBranchesTool` | Update — join via `repository_id` |
| `ReviewsIndexMapper` | Update — uses `repository_id` internally; JSON output unchanged |
| Webhook handlers | Update — upsert-by-`url`, use returned `repository_id` |
| `CommentChangeDetector` | New — diffs kalynx-reviews commit, extracts `(reviewId, commentId, subStream)` tuples, classifies event type |
| `CommentEventType` | New — enum: `ADDED`, `UPDATED` |
| `CommentsIndexRepository` | New — upsert and query `comments_index`; joins `repositories` for URL |
| `CommentsHandler` | New — handles `GET /reviews/{reviewId}/comments` |
| `Application` | Extend — wire `CommentChangeDetector` into `kalynxReviewsUpdateCallback` |
| `IndexerHttpServer` | Extend — register `/reviews/{id}/comments` with auth |
| `SseHandler` | Extend — serialise `comment.added` / `comment.updated` with `comment_id` in payload |

### SSE Event Types

| Event | Action |
|---|---|
| `comment.added` | New — fired when `metadata` or `text` sub-stream changes; payload: `{review_id, repository_url, comment_id}` |
| `comment.updated` | New — fired when `status` sub-stream changes; payload: `{review_id, repository_url, comment_id}` |
