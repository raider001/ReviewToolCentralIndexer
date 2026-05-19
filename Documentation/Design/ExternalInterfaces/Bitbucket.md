# Bitbucket — External Interface

## Overview

Bitbucket integration listens for **push events**. The indexer only cares about pushes that update a `refs/notes/reviews/*` ref — these indicate that review data stored as git notes has changed. All other push events are acknowledged and discarded.

The plugin does not interact with Bitbucket's native Pull Request system at all.

Both **Bitbucket Cloud** (bitbucket.org) and **Bitbucket Data Center** (self-hosted) are supported. The webhook payload format is largely consistent between the two, with differences noted below.

---

## Webhook Configuration

### Endpoint registered by the plugin

```
POST /webhooks/bitbucket
```

### Bitbucket Cloud webhook setup (per repository)

1. Go to **Repository Settings → Webhooks → Add webhook**.
2. Set **URL** to `https://<indexer-host>/webhooks/bitbucket`.
3. Set a **Secret** for request signing.
4. Under triggers, select **Repository → Push** only.
5. Enable **Active**.

### Bitbucket Data Center webhook setup

1. Go to **Repository Settings → Webhooks → Create webhook**.
2. Set **URL** to `https://<indexer-host>/webhooks/bitbucket`.
3. Under events, select **Repository → Push** only.
4. A secret token is supported from Data Center 7.x onwards.

### Signature verification

**Bitbucket Cloud** signs requests using `HMAC-SHA256`:

```
X-Hub-Signature: sha256=<hmac-hex>
X-Event-Key: repo:push
X-Request-UUID: <uuid>   ← delivery ID used for deduplication
```

**Bitbucket Data Center** uses the same `X-Hub-Signature` header on 7.x+. Older versions may omit the signature entirely — treat unsigned requests as trusted only when the indexer is on a private network.

---

## Push Payload — Relevant Fields

### Bitbucket Cloud

```json
{
  "repository": {
    "name": "my-repo"
  },
  "push": {
    "changes": [
      {
        "new": {
          "name": "refs/notes/reviews/abc123/metadata/status",
          "type": "branch"
        },
        "commits": [
          {
            "hash": "<sha>",
            "date": "2026-05-19T10:30:00Z",
            "author": { "user": { "nickname": "jane.doe" } }
          }
        ]
      }
    ]
  }
}
```

### Bitbucket Data Center

```json
{
  "repository": {
    "slug": "my-repo"
  },
  "refChanges": [
    {
      "refId": "refs/notes/reviews/abc123/metadata/status",
      "toHash": "<sha>",
      "type": "UPDATE"
    }
  ],
  "actor": {
    "name": "jane.doe"
  }
}
```

The plugin filters on `push.changes[].new.name` (Cloud) or `refChanges[].refId` (Data Center) under two separate rules:

### Rule 1 — Review notes push (`refs/notes/reviews/*`)

If the ref starts with `refs/notes/reviews/` → extract the review ID and stream name, map to a `ReviewEvent`, submit via `EventSink`.

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

If the ref starts with `refs/heads/` → emit a `BRANCH_UPDATED` event. Clients use this to detect that new commits have landed on a branch that may be under review and refresh the commit list for any open reviews referencing that branch.

#### Event type mapping — branch refs

| `ref` pattern | Mapped `ReviewEvent.EventType` | Notes |
|---|---|---|
| `refs/heads/*` (commits added) | `BRANCH_UPDATED` | `reviewId` is left blank; `payload` carries `{"branch":"<name>","headSha":"<sha>"}` |
| `refs/heads/*` (branch deleted — Cloud: `new` is null; Data Center: `type` is `DELETE`) | `BRANCH_DELETED` | Signals that a review branch no longer exists |

Any ref that matches neither rule is acknowledged with `200 OK` and discarded.

---

## Reconciliation (Gap Recovery)

Called at startup via `ProviderPlugin.reconcile(repository, since)`.

### Bitbucket Cloud API

```
GET https://api.bitbucket.org/2.0/repositories/{workspace}/{slug}/refs/branches
    ?q=name ~ "refs/notes/reviews/"&pagelen=100
```

Bitbucket Cloud does not have a push history API that filters by ref prefix and timestamp cleanly. The most reliable approach is to list all `refs/notes/reviews/*` refs currently present and compare against what the indexer already knows, submitting synthetic events for any reviewIds not yet in the store.

### Bitbucket Data Center API

```
GET https://<host>/rest/api/1.0/projects/{key}/repos/{slug}/branches
    ?filterText=refs/notes/reviews/&limit=100&orderBy=MODIFICATION
```

Same approach as Cloud — enumerate current notes refs and reconcile against stored state.

**Limitation:** Neither Bitbucket variant provides a push history API with ref-level filtering. Reconciliation cannot reconstruct the precise sequence of changes that occurred during downtime — it can only detect which reviews exist or have been updated. Missing intermediate events (e.g. a comment added then deleted during the outage) will not be recoverable.

### Authentication

| Variant | Method |
|---|---|
| Bitbucket Cloud | App password or OAuth 2.0 with `repository:read` scope |
| Bitbucket Data Center | HTTP Basic with personal access token |

### Configuration keys

```yaml
repositories:
  - name: my-repo
    provider: bitbucket
    variant: cloud           # cloud | datacenter
    webhookSecret: "<hmac-secret>"
    workspace: "my-workspace"   # Cloud only
    projectKey: "PROJ"          # Data Center only
    repoSlug: "my-repo"
    username: "svc-account"
    appPassword: "<token>"
```

---

## Retry Behaviour

**Bitbucket Cloud** retries failed webhook deliveries up to **5 times** over approximately **1 hour**. Reconciliation covers the gap if downtime exceeds this window.

**Bitbucket Data Center** does not retry failed webhooks. Reconciliation at startup is the only safety net for missed events.

The plugin deduplicates on `X-Request-UUID` to prevent double-inserting retried events.

---

## Rate Limits

| Variant | Limit |
|---|---|
| Bitbucket Cloud (authenticated) | 1,000 requests/hour per user |
| Bitbucket Data Center | Configurable by admin; no default hard limit |

