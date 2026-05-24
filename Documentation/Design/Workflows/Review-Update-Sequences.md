# Review Update Sequences

Covers webhook events that change review metadata. All events arrive on the `kalynx-reviews` orphan branch.

---

## Review Created

A new review file appears in the commit tree. SSE fires immediately — the git read for `branch_name` happens in parallel to populate `review_branches`.

```mermaid
sequenceDiagram
    participant Provider as Git Provider
    participant Indexer
    participant Git as Git Repository
    participant DB as PostgreSQL
    participant Client

    Provider->>Indexer: POST /webhook (commit.pushed, kalynx-reviews)
    Indexer->>Indexer: in-memory dedup check

    alt duplicate
        Indexer-->>Provider: 200 OK (discarded)
    else new
        par
            Indexer->>Git: git cat-file --batch (read branch_name)
            Git-->>Indexer: branch_name
            Indexer->>DB: INSERT reviews (review_id, last_updated)
            Indexer->>DB: INSERT review_branches (review_id, repository, branch_name)
        and
            Indexer-->>Client: SSE review.created + review_id
        end

        Indexer-->>Provider: 200 OK
    end
```

---

## Review Status Updated

An existing review file is updated on the `kalynx-reviews` branch. SSE fires immediately — the git read for status happens in parallel to keep the DB filter index current.

```mermaid
sequenceDiagram
    participant Provider as Git Provider
    participant Indexer
    participant Git as Git Repository
    participant DB as PostgreSQL
    participant Client

    Provider->>Indexer: POST /webhook (commit.pushed, kalynx-reviews)
    Indexer->>Indexer: in-memory dedup check

    alt duplicate
        Indexer-->>Provider: 200 OK (discarded)
    else new
        par
            Indexer->>Git: git cat-file --batch (read status)
            Git-->>Indexer: status
            Indexer->>DB: UPDATE reviews SET status, last_updated
        and
            Indexer-->>Client: SSE review.updated + review_id
        end

        Indexer-->>Provider: 200 OK
    end
```
