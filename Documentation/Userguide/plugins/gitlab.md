# GitLab Plugin

**Provider ID:** `"gitlab"`  
**Webhook path:** `POST /webhooks/gitlab`

## Overview

The GitLab plugin receives push event webhooks from GitLab.com or a self-hosted GitLab instance. GitLab passes its webhook secret as a plain header rather than an HMAC digest; the plugin compares it using a constant-time algorithm to prevent timing attacks.

Startup reconciliation enumerates the current `refs/notes/reviews/*` branches via the GitLab Branches API and emits synthetic `REVIEW_UPDATED` events for any reviews found.

---

## Configuration

```json
"plugin": {
  "providerId": "gitlab",
  "properties": {
    "webhookSecret": "${GITLAB_WEBHOOK_SECRET}",
    "apiToken":      "${GITLAB_API_TOKEN}",
    "baseUrl":       "https://gitlab.example.com",
    "projectId":     "42"
  }
}
```

### Property Reference

| Property | Required | Default | Description |
|---|---|---|---|
| `webhookSecret` | Yes | — | The secret you enter when registering the webhook in GitLab. Compared against the `X-Gitlab-Token` header on every delivery. |
| `apiToken` | Yes (for reconciliation) | — | A GitLab personal access token or project access token with `read_repository` scope. Required for the Branches API reconciliation calls. Without this, reconciliation is skipped with a warning. |
| `baseUrl` | No | `https://gitlab.com` | Base URL of your GitLab instance. For GitLab.com, omit this property or set it to `https://gitlab.com`. |
| `projectId` | No | `{namespace}%2F{repo}` | The GitLab project ID (numeric) or URL-encoded path (e.g. `"my-group%2Fmy-project"`). If omitted, the repository identifier from `repositories` is URL-encoded and used as the path. |

### Per-Repository Webhook Secrets

If you manage multiple projects with different secrets, set per-project overrides:

```json
"properties": {
  "webhookSecret": "global-fallback-secret",
  "my-group/project-a.webhookSecret": "secret-for-project-a"
}
```

The key format is `{namespace}/{project}.webhookSecret`. The global `webhookSecret` is the fallback for any repository without a specific override.

---

## Token Verification

GitLab places the webhook secret in the `X-Gitlab-Token` header as a plain string — **not** as an HMAC digest. The plugin performs a constant-time byte comparison to prevent timing attacks. Requests with a missing or incorrect token are rejected.

> This differs from GitHub and Bitbucket, which use HMAC-SHA256. The difference is a deliberate GitLab design choice.

---

## Registering a Webhook in GitLab

### Per-Project Webhook

1. Navigate to your project → **Settings** → **Webhooks**.
2. Set **URL** to `https://<your-indexer-host>/webhooks/gitlab`.
3. Enter a **Secret token** matching `webhookSecret` in your config.
4. Under **Trigger**, tick **Push events**.
5. Click **Add webhook**.

### Group Webhook (GitLab Premium/Ultimate)

1. Navigate to your group → **Settings** → **Webhooks**.
2. Follow the same steps as above.

GitLab will fire a `ping` test immediately. The indexer accepts the delivery and routes it through the `X-Gitlab-Event: Push Hook` check, where `"ping"` events are discarded silently.

---

## Reconciliation

On startup, the plugin calls:

```
GET {baseUrl}/api/v4/projects/{projectId}/repository/branches
    ?search=refs%2Fnotes%2Freviews%2F&per_page=100
```

authenticated with the `PRIVATE-TOKEN` header. It emits a `REVIEW_UPDATED` event for each `refs/notes/reviews/*` branch it finds.

**Requirements:** `apiToken` must be set and the token must have at least `read_repository` (or `Reporter` role) on the project.

---

## Self-Hosted GitLab

Set `baseUrl` to your instance URL and ensure the indexer can reach it over HTTPS (or HTTP for internal deployments):

```json
"properties": {
  "baseUrl":       "https://gitlab.internal.example.com",
  "webhookSecret": "${GITLAB_SECRET}",
  "apiToken":      "${GITLAB_TOKEN}"
}
```

If your self-hosted instance uses a self-signed certificate, configure the JVM trust store:

```bash
java -Djavax.net.ssl.trustStore=/path/to/truststore.jks \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -jar central-indexer.jar
```

---

## Full Config Example

```json
{
  "plugin": {
    "providerId": "gitlab",
    "properties": {
      "webhookSecret": "${GITLAB_WEBHOOK_SECRET}",
      "apiToken":      "${GITLAB_API_TOKEN}",
      "baseUrl":       "https://gitlab.example.com",
      "projectId":     "my-group%2Fbackend"
    }
  },
  "repositories": [
    "my-group/backend",
    "my-group/frontend"
  ]
}
```

### Multi-Repository with Per-Project Secrets

```json
{
  "plugin": {
    "providerId": "gitlab",
    "properties": {
      "webhookSecret":                     "fallback-secret",
      "my-group/backend.webhookSecret":    "${BACKEND_SECRET}",
      "my-group/frontend.webhookSecret":   "${FRONTEND_SECRET}",
      "apiToken":                          "${GITLAB_API_TOKEN}",
      "baseUrl":                           "https://gitlab.example.com"
    }
  },
  "repositories": [
    "my-group/backend",
    "my-group/frontend"
  ]
}
```

