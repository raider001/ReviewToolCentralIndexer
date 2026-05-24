# Central Indexer — Database Storage Design

## Technology

PostgreSQL via a custom blocking JDBC connection pool (`ConnectionPool`, pool size configured in `config.json`, default 20). `pg_notify` is used for SSE fan-out — all writes notify `ListenThread`, which forwards to open SSE connections.

---

## Schema

### Tables

#### repositories
Required to let the client know what repositories to query for more detail.

#### branches
Required to let the client determine code changes and to track branch head commits for review updates.

#### review_branches
Required to let the client determine which branches are being watched by a review.

#### reviews
Required to let the client determine which reviews are being watched.


## Entity Relationship Diagram

```mermaid
erDiagram
	repositories {
		TEXT owner PK
		TEXT repository PK
		TEXT url
	}

	branches {
		TEXT owner PK, FK
		TEXT repository PK, FK
		TEXT branch_name PK
		TEXT head_commit
	}

	reviews {
		TEXT review_id PK
		TEXT status
		TIMESTAMPTZ last_updated
	}

	review_branches {
		TEXT review_id PK, FK
		TEXT owner PK, FK
		TEXT repository PK, FK
		TEXT branch_name PK, FK
	}

	repositories ||--o{ branches : "contains"
	branches ||--o{ review_branches : "tracked by"
	reviews ||--o{ review_branches : "watched via"
	repositories ||--o{ review_branches : "referenced by"
```


---

## Read-Optimized Index (reviews_index)

Keep the normalized tables but maintain a denormalized read table that serves `GET /reviews` with low latency.

**Schema:**

```sql
CREATE TABLE IF NOT EXISTS reviews_index (
    review_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    last_updated TIMESTAMPTZ NOT NULL,
    repositories JSONB
);
```

**Indexes:**

```sql
CREATE INDEX IF NOT EXISTS idx_reviews_index_last_updated
    ON reviews_index (last_updated DESC);

CREATE INDEX IF NOT EXISTS idx_reviews_index_repositories_gin
    ON reviews_index USING GIN (repositories);
```

**JSONB Structure:**

The `repositories` JSONB column stores a denormalized array with complete branch information:

```json
[
  {
    "owner": "alice",
    "repository": "repo-a",
    "repositoryUrl": "https://github.com/alice/repo-a",
    "branchName": "main",
    "headCommit": "c0ffee123"
  },
  {
    "owner": "alice",
    "repository": "repo-a",
    "repositoryUrl": "https://github.com/alice/repo-a",
    "branchName": "develop",
    "headCommit": "deadbeef456"
  }
]
```

**Maintenance:**

- The indexer upserts this denormalized JSON on every review event
- Array entries are deterministically sorted by `owner`, `repository`, `branchName`
- Mapping logic is in `ReviewsIndexMapper.toRepositoriesJson()`

