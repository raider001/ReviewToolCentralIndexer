# GitHub Plugin

**Provider ID:** `"github"`  
**Webhook path:** `POST /webhooks/github`

## Overview

The GitHub plugin receives push event webhooks from GitHub, verifies the HMAC-SHA256 signature, and converts matching ref updates into review events. It also reconciles missed events on startup by paging through the GitHub Events API.

---

## Configuration

Set `plugin.providerId` to `"github"` and provide the required properties:

```json
"plugin": {
  "providerId": "github",
  "properties": {
    "webhookSecret": "${GITHUB_WEBHOOK_SECRET}",
    "apiToken":      "${GITHUB_API_TOKEN}"
  }
}
```

### Property Reference

| Property | Required | Description |
|---|---|---|
| `webhookSecret` | Yes | The secret you set when registering the webhook in GitHub. Used to verify the `X-Hub-Signature-256` header. |
| `apiToken` | Yes (for reconciliation) | A GitHub personal access token or fine-grained token with `repo:read` access. Needed to call the Events API during startup reconciliation. Without this, reconciliation is skipped with a warning. |

### Per-Repository Webhook Secrets

If different repositories use different webhook secrets, set a per-repository override:

```json
"properties": {
  "webhookSecret": "global-fallback-secret",
  "my-org/repo-a.webhookSecret": "secret-for-repo-a",
  "my-org/repo-b.webhookSecret": "secret-for-repo-b"
}
```

The key format is `{owner}/{repo}.webhookSecret`. The global `webhookSecret` is used for any repository that does not have a per-repository override.

---

## Registering the Webhook in GitHub

1. Navigate to your repository (or organisation) → **Settings** → **Webhooks** → **Add webhook**.
2. Set **Payload URL** to `https://<your-indexer-host>/webhooks/github`.
3. Set **Content type** to `application/json`.
4. Set **Secret** to the same value as `webhookSecret` in your config.
5. Under **Which events**, select **Just the push event**.
6. Click **Add webhook**.

GitHub will send a `ping` event immediately after creation. The indexer ignores `ping` events silently.

---

## Signature Verification

GitHub signs every webhook payload with HMAC-SHA256 using your webhook secret. The signature is sent in the `X-Hub-Signature-256` header in the format:

```
X-Hub-Signature-256: sha256=<hex-digest>
```

The plugin rejects any request where the header is absent or the signature does not match, logging a warning. A constant-time comparison is used to prevent timing attacks.

---

## Reconciliation

On startup, the plugin pages through `GET /repos/{owner}/{repo}/events` on the GitHub API (up to 100 events per page, up to 300 total) and replays any `PushEvent` entries targeting `refs/notes/reviews/*` that occurred after the last recorded event.

**Limitations:**
- GitHub retains events for **90 days** and returns a maximum of **300 events** per query. Events beyond this window cannot be recovered.
- Without an `apiToken`, the unauthenticated rate limit is 60 requests per hour. With a token, the limit is 5,000 requests per hour.

---

## Full Config Example

```json
{
  "plugin": {
    "providerId": "github",
    "properties": {
      "webhookSecret": "${GITHUB_WEBHOOK_SECRET}",
      "apiToken":      "${GITHUB_API_TOKEN}",
      "my-org/special-repo.webhookSecret": "${SPECIAL_REPO_SECRET}"
    }
  },
  "repositories": [
    "my-org/backend",
    "my-org/frontend",
    "my-org/special-repo"
  ]
}
```

