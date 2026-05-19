# GitLab — External Interface

## Overview

GitLab integration listens for **push events**. The indexer only cares about pushes that update a `refs/notes/reviews/*` ref — these indicate that review data stored as git notes has changed. All other push events are acknowledged and discarded.

The plugin does not interact with GitLab's native Merge Request system at all.

Both **GitLab.com** (cloud) and **GitLab Self-Managed** are supported. The webhook payload format and REST API are identical between the two — only the base URL and token type differ.

---

## Webhook Configuration

### Endpoint registered by the plugin

```
POST /webhooks/gitlab
```

### GitLab webhook setup (per project)

1. Go to **Settings → Webhooks**.
2. Set **URL** to `https://<indexer-host>/webhooks/gitlab`.
3. Set **Secret token** to the `webhookSecret` value from the indexer configuration.
4. Under triggers, enable **Push events** only.
5. Enable **SSL verification** if the indexer is reachable over HTTPS.
6. Click **Add webhook**.

Organization-level webhooks (GitLab Ultimate) use the same endpoint and payload format.

### Signature verification

GitLab passes the secret token as a plain header — it is not HMAC-signed:

```
X-Gitlab-Token: <secret>       ← compared directly against configured webhookSecret
X-Gitlab-Event: Push Hook
X-Gitlab-Event-UUID: <uuid>    ← delivery ID used for deduplication
```

The plugin must compare `X-Gitlab-Token` against the configured secret using a **constant-time comparison** to prevent timing attacks.

---

## Push Payload — Relevant Fields

```json
{
  "ref": "refs/notes/reviews/abc123/metadata/status",
  "repository": {
    "name": "my-repo"
  },
  "user_username": "jane.doe",
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

If `ref` starts with `refs/notes/reviews/` → extract the review ID and stream name, map to a `ReviewEvent`, submit via `EventSink`.

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
| `refs/heads/*` (branch deleted — `after` is all zeros) | `BRANCH_DELETED` | Signals that a review branch no longer exists |

Any ref that matches neither rule is acknowledged with `200 OK` and discarded.

---

## Reconciliation (Gap Recovery)

Called at startup via `ProviderPlugin.reconcile(repository, since)`.

### API used

```
GET https://<host>/api/v4/projects/{id}/repository/branches
    ?search=refs/notes/reviews/&per_page=100
```

GitLab does not expose a push history API with ref-level filtering. The most reliable approach is to enumerate all current `refs/notes/reviews/*` branches and compare against stored state, submitting synthetic events for any reviewIds not yet in the store.

For more precise recovery (detecting which specific stream changed), the commits API can be queried per ref:

```
GET https://<host>/api/v4/projects/{id}/repository/commits
    ?ref_name=refs/notes/reviews/{reviewId}/{stream}&since=<ISO8601>
```

This gives the actual commit timestamps for each notes ref, allowing the plugin to determine whether a change occurred after `since` and emit an appropriately typed event.

### Authentication

| Type | Header |
|---|---|
| Personal Access Token | `PRIVATE-TOKEN: <token>` |
| Project Access Token | `PRIVATE-TOKEN: <token>` |
| OAuth 2.0 | `Authorization: Bearer <token>` |

Minimum scope required: `read_repository`.

### Configuration keys

```yaml
repositories:
  - name: my-repo
    provider: gitlab
    webhookSecret: "<secret-token>"
    baseUrl: "https://gitlab.com"      # or self-managed host
    projectId: "12345678"              # numeric project ID or namespace/repo path
    apiToken: "<personal-access-token>"
```

---

## Retry Behaviour

GitLab retries failed webhook deliveries **3 times** with short delays (approximately 15 seconds between attempts). If all three fail, no further retries occur. Reconciliation at startup covers any gaps beyond this window.

The plugin deduplicates on `X-Gitlab-Event-UUID` to prevent double-inserting retried events.

---

## Rate Limits

| Variant | Limit |
|---|---|
| GitLab.com (authenticated) | 2,000 requests/minute per user |
| GitLab Self-Managed | Configurable by admin; defaults to 300 requests/minute |

Reconciliation pages with `per_page=100` and stops when no refs updated after `since` remain.

