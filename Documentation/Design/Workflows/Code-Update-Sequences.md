# Code Update Sequences

Covers webhook events that change what code is under review — which branches and repositories are associated, and when commits arrive. 

> **Closed reviews are frozen snapshots.** Any event that would modify or notify a closed review is skipped. The state at closure is preserved as-is.

---

## Commit Pushed

A commit is pushed to a tracked branch. The indexer matches the push against its in-memory registry of active client connections and fires SSE directly. No DB interaction.

```mermaid
sequenceDiagram
    participant Provider as Git Provider
    participant Indexer
    participant Client

    Provider->>Indexer: POST /webhook (commit.pushed, repository, branch_name, head_commit)
    Indexer->>Indexer: in-memory dedup check

    alt duplicate
        Indexer-->>Provider: 200 OK (discarded)
    else new
        Indexer->>Indexer: match (repository, branch_name) against active SSE connections

        loop per matching review_id
            Indexer-->>Client: SSE branch.updated + review_id + head_commit
        end

        Indexer-->>Provider: 200 OK
    end
```

---

## Branch Created

A new branch appears in a tracked repository. The indexer records it and checks whether any open reviews should now be associated with it.

```mermaid
sequenceDiagram
    participant Provider as Git Provider
    participant Indexer
    participant DB as PostgreSQL
    participant Client

    Provider->>Indexer: POST /webhook (branch.created, repository, branch_name)
    Indexer->>Indexer: in-memory dedup check

    alt duplicate
        Indexer-->>Provider: 200 OK (discarded)
    else new
        Indexer->>DB: INSERT branches (repository, branch_name)
        Indexer->>DB: SELECT review_id FROM reviews WHERE branch_name matches AND status = 'OPEN'
        DB-->>Indexer: [review_id, ...]

        alt no matching open reviews
            Indexer-->>Provider: 200 OK (no action)
        else open reviews found
            par
                loop per review_id
                    Indexer->>DB: INSERT review_branches (review_id, repository, branch_name)
                end
            and
                loop per review_id
                    Indexer-->>Client: SSE review.updated + review_id
                end
            end

            Indexer-->>Provider: 200 OK
        end
    end
```

---

## Branch Deleted

A branch is deleted from a tracked repository. Open reviews watching it are notified and their association removed. Closed reviews are left untouched — the snapshot is preserved.

```mermaid
sequenceDiagram
    participant Provider as Git Provider
    participant Indexer
    participant DB as PostgreSQL
    participant Client

    Provider->>Indexer: POST /webhook (branch.deleted, repository, branch_name)
    Indexer->>Indexer: in-memory dedup check

    alt duplicate
        Indexer-->>Provider: 200 OK (discarded)
    else new
        Indexer->>DB: SELECT review_id FROM review_branches rb JOIN reviews r ON r.review_id = rb.review_id WHERE rb.repository + rb.branch_name AND r.status = 'OPEN'
        DB-->>Indexer: [review_id, ...]

        par
            Indexer->>DB: DELETE branches WHERE repository + branch_name
            Note over DB: cascades to review_branches for closed reviews only
            loop per open review_id
                Indexer->>DB: DELETE review_branches WHERE review_id + repository + branch_name
            end
        and
            loop per review_id
                Indexer-->>Client: SSE branch.deleted + review_id + branch_name
            end
        end

        Indexer-->>Provider: 200 OK
    end
```

---

## Repository Associated to Review

The same review file (same `review_id`) appears in an additional repository's `kalynx-reviews` branch, linking that repo's branch to the review. Skipped if the review is closed.

```mermaid
sequenceDiagram
    participant Provider as Git Provider
    participant Indexer
    participant Git as Git Repository B
    participant DB as PostgreSQL
    participant Client

    Provider->>Indexer: POST /webhook (commit.pushed, kalynx-reviews, repo-b)
    Indexer->>Indexer: in-memory dedup check

    alt duplicate
        Indexer-->>Provider: 200 OK (discarded)
    else new
        Indexer->>DB: SELECT status FROM reviews WHERE review_id
        DB-->>Indexer: status

        alt review is CLOSED
            Indexer-->>Provider: 200 OK (frozen — no action)
        else review is OPEN
            par
                Indexer->>Git: git cat-file --batch (read branch_name)
                Git-->>Indexer: branch_name
                Indexer->>DB: UPSERT reviews (review_id, last_updated)
                Indexer->>DB: INSERT review_branches (review_id, repo-b, branch_name)
            and
                Indexer-->>Client: SSE review.updated + review_id
            end

            Indexer-->>Provider: 200 OK
        end
    end
```

---

## Repository Dissociated from Review

The review file is deleted from a repository's `kalynx-reviews` branch. Skipped if the review is closed — the frozen snapshot retains the association.

```mermaid
sequenceDiagram
    participant Provider as Git Provider
    participant Indexer
    participant DB as PostgreSQL
    participant Client

    Provider->>Indexer: POST /webhook (commit.pushed, kalynx-reviews, repo-b)
    Indexer->>Indexer: in-memory dedup check

    alt duplicate
        Indexer-->>Provider: 200 OK (discarded)
    else new
        Indexer->>DB: SELECT status FROM reviews WHERE review_id
        DB-->>Indexer: status

        alt review is CLOSED
            Indexer-->>Provider: 200 OK (frozen — no action)
        else review is OPEN
            Note over Indexer: file absent in tree — treat as dissociation

            par
                Indexer->>DB: DELETE review_branches WHERE review_id + repo-b
            and
                Indexer-->>Client: SSE review.updated + review_id
            end

            Indexer-->>Provider: 200 OK
        end
    end
```


