# GitHub — External Interface

## Overview

GitHub integration listens for **push events**. The indexer only cares about pushes that update a `refs/notes/reviews/*` ref — these indicate that review data stored as git notes has changed. All other push events are acknowledged and discarded.

The plugin does not interact with GitHub's native Pull Request system at all.

---

## Webhook Configuration

### Endpoint registered by the plugin

```
POST /webhooks/github
```

### GitHub webhook setup (per repository or organisation)

1. Go to **Settings → Webhooks → Add webhook** on the repository or organisation.
2. Set **Payload URL** to `https://<indexer-host>/webhooks/github`.
3. Set **Content type** to `application/json`.
4. Set **Secret** to the `webhookSecret` value from the indexer configuration.
5. Under **Which events**, select **Let me select individual events** and enable only **Pushes**.
6. Ensure **Active** is checked.

### Signature verification

GitHub signs every request with `HMAC-SHA256` using the configured secret. The plugin must verify the `X-Hub-Signature-256` header before processing.

```
X-Hub-Signature-256: sha256=<hmac-hex>
X-GitHub-Event: push
X-GitHub-Delivery: <uuid>   ← delivery ID used for deduplication
```

---

## Push Payload — Relevant Fields

```json
{
  "ref": "refs/notes/reviews/abc123/metadata/status",
  "repository": {
    "name": "my-repo"
  },
  "pusher": {
    "name": "jane.doe"
  },
  "commits": [
    {
      "id": "<sha>",
      "timestamp": "2026-05-19T10:30:00Z"
    }
  ]
}
```

The plugin processes the `ref` field under two separate rules:

### Rule 1 — Review notes push (`refs/notes/reviews/*`)

If `ref` starts with `refs/notes/reviews/` → extract the review ID and stream name, map to a `ReviewEvent`, submit via `EventSink`. Any other `ref` that does not match Rule 2 is acknowledged with `200 OK` and discarded.

#### Extracting the review ID

The ref format is:

```
refs/notes/reviews/{reviewId}/{streamName}
```

For example:
```
refs/notes/reviews/01890a5d-ac96-774b-bcce-b302099a8057/metadata/status
```

The `reviewId` is the segment immediately after `refs/notes/reviews/`. The `streamName` indicates what changed but the indexer emits a typed event — clients decide what to reload.

#### Event type mapping — notes refs

| `ref` pattern | Mapped `ReviewEvent.EventType` |
|---|---|
| `.../metadata/title` (first push, no prior events for this reviewId) | `REVIEW_CREATED` |
| `.../metadata/title` (subsequent push) | `REVIEW_UPDATED` |
| `.../metadata/status` | `REVIEW_UPDATED` |
| `.../reviewers` | `REVIEW_UPDATED` |
| `.../comments/*/text` | `REVIEW_COMMENT_ADDED` |
| `.../comments/*/status` | `REVIEW_COMMENT_UPDATED` |
| Any other notes ref for this reviewId | `REVIEW_UPDATED` |

The plugin detects `REVIEW_CREATED` by checking whether the repository has any prior stored events for the given `reviewId`.

### Rule 2 — Standard branch push (`refs/heads/*`)

If `ref` starts with `refs/heads/` → emit a `BRANCH_UPDATED` event. Clients use this to detect that new commits have landed on a branch that may be under review and refresh the commit list for any open reviews referencing that branch.

#### Event type mapping — branch refs

| `ref` pattern | Mapped `ReviewEvent.EventType` | Notes |
|---|---|---|
| `refs/heads/*` (commits added) | `BRANCH_UPDATED` | `reviewId` is left blank; `payload` carries `{"branch":"<name>","headSha":"<sha>"}` |
| `refs/heads/*` (branch deleted, `after` is all zeros) | `BRANCH_DELETED` | Signals that a review branch no longer exists |

---

## Reconciliation (Gap Recovery)

Called at startup via `ProviderPlugin.reconcile(repository, since)`.

### API used

```
GET https://api.github.com/repos/{owner}/{repo}/events?per_page=100
```

The Events API returns recent repository events. The plugin pages through results filtering for `PushEvent` entries where any ref in `payload.commits` updated a `refs/notes/reviews/*` ref, stopping when `created_at` falls before `since`.

**Limitation:** GitHub retains repository events for only **90 days** and returns at most **300 events** per query. For heavily active repositories, events beyond this window are unrecoverable via the API — reconciliation will silently miss them. Keeping indexer downtime short and relying on GitHub's webhook retry window (3 days) is the primary recovery mechanism.

### Authentication

```
Authorization: Bearer <personal-access-token>
```

Requires `repo` scope (read-only is sufficient via a fine-grained token with **Contents: Read**).

### Configuration keys

```yaml
repositories:
  - name: my-repo
    provider: github
    webhookSecret: "<hmac-secret>"
    apiToken: "<personal-access-token>"
    repoOwner: "my-org"
    repoSlug: "my-repo"
```

---

## Retry Behaviour

GitHub retries failed webhook deliveries with exponential backoff for up to **3 days**. The plugin deduplicates on `X-GitHub-Delivery` to prevent double-inserting retried events.

---

## Rate Limits

| Authentication | Limit |
|---|---|
| Authenticated (token) | 5,000 requests/hour |
| Unauthenticated | 60 requests/hour |

Reconciliation pages with `per_page=100` and stops as soon as events are older than `since`.

