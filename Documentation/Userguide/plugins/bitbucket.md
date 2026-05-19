# Bitbucket Plugin

**Provider ID:** `"bitbucket"`  
**Webhook path:** `POST /webhooks/bitbucket`

## Overview

The Bitbucket plugin supports both **Bitbucket Cloud** (`bitbucket.org`) and **Bitbucket Data Center** (self-hosted). The two variants have different payload formats and API endpoints; the plugin detects the payload format automatically at runtime.

---

## Variants

| Variant | Value | Description |
|---|---|---|
| Cloud | `"cloud"` (default) | Hosted at `bitbucket.org`. Payloads include a `"push"` key. |
| Data Center | `"datacenter"` | Self-hosted Bitbucket DC/Server. Payloads include a `"refChanges"` key. |

Set the `variant` property to switch behaviour:

```json
"plugin": {
  "providerId": "bitbucket",
  "properties": {
    "variant": "datacenter"
  }
}
```

---

## Bitbucket Cloud Configuration

```json
"plugin": {
  "providerId": "bitbucket",
  "properties": {
    "variant":       "cloud",
    "webhookSecret": "${BB_WEBHOOK_SECRET}",
    "username":      "my-bitbucket-user",
    "appPassword":   "${BB_APP_PASSWORD}"
  }
}
```

### Cloud Property Reference

| Property | Required | Description |
|---|---|---|
| `variant` | No | `"cloud"` (default). |
| `webhookSecret` | Yes | The secret configured on the webhook in Bitbucket Cloud. Used to verify the `X-Hub-Signature: sha256=<hex>` header. |
| `username` | Yes (for reconciliation) | Your Bitbucket username. Used with `appPassword` to authenticate Branches API calls during reconciliation. |
| `appPassword` | Yes (for reconciliation) | A Bitbucket [App Password](https://support.atlassian.com/bitbucket-cloud/docs/app-passwords/) with `Repositories: Read` permission. |

### Registering a Webhook in Bitbucket Cloud

1. Navigate to your repository → **Repository Settings** → **Webhooks** → **Add webhook**.
2. Set **URL** to `https://<your-indexer-host>/webhooks/bitbucket`.
3. Under **Triggers**, select **Repository push**.
4. Enter a **Secret** matching `webhookSecret` in your config.
5. Click **Save**.

---

## Bitbucket Data Center Configuration

```json
"plugin": {
  "providerId": "bitbucket",
  "properties": {
    "variant":    "datacenter",
    "baseUrl":    "https://bitbucket.example.com",
    "projectKey": "PROJ",
    "username":   "svc-indexer",
    "appPassword": "${BB_DC_PASSWORD}",
    "webhookSecret": "${BB_DC_WEBHOOK_SECRET}"
  }
}
```

### Data Center Property Reference

| Property | Required | Description |
|---|---|---|
| `variant` | Yes | Must be `"datacenter"`. |
| `baseUrl` | Yes | Base URL of your Bitbucket Data Center instance, e.g. `https://bitbucket.example.com`. |
| `projectKey` | Yes | The project key that contains the repositories listed in `repositories`. |
| `username` | Yes (for reconciliation) | Service account username with read access to the project. |
| `appPassword` | Yes (for reconciliation) | HTTP access token or password for the service account. |
| `webhookSecret` | Yes | Secret configured on the webhook. Used to verify `X-Hub-Signature`. |
| `allowUnsigned` | No | Set to `"true"` to accept webhooks that have no signature header. **Only for Data Center versions older than 7.x** that do not support webhook signing. |

### Registering a Webhook in Bitbucket Data Center

1. Navigate to your repository → **Repository Settings** → **Webhooks** → **Create webhook**.
2. Set **URL** to `https://<your-indexer-host>/webhooks/bitbucket`.
3. Set **Secret** to match `webhookSecret` in your config.
4. Under **Events**, select **Push**.
5. Click **Save**.

---

## Signature Verification

Bitbucket sends the signature in the `X-Hub-Signature` header using the format:

```
X-Hub-Signature: sha256=<hex-digest>
```

The plugin verifies HMAC-SHA256 against the raw request body. Requests with invalid or missing signatures are rejected unless `allowUnsigned` is set to `"true"`.

> **Security note:** Only enable `allowUnsigned` on isolated internal networks. It removes a critical layer of request authentication.

---

## Per-Repository Webhook Secrets

```json
"properties": {
  "webhookSecret": "global-fallback-secret",
  "workspace/repo-a.webhookSecret": "secret-for-repo-a"
}
```

The key format is `{workspace}/{slug}.webhookSecret` for Cloud, or `{repoSlug}.webhookSecret` for Data Center.

---

## Reconciliation

On startup, the plugin calls the Branches API to enumerate all branches matching `refs/notes/reviews/*` and emits a `REVIEW_UPDATED` event for each review found.

- **Cloud**: `GET https://api.bitbucket.org/2.0/repositories/{workspace}/{slug}/refs/branches`
- **Data Center**: `GET {baseUrl}/rest/api/1.0/projects/{projectKey}/repos/{slug}/branches`

---

## Full Cloud Config Example

```json
{
  "plugin": {
    "providerId": "bitbucket",
    "properties": {
      "variant":       "cloud",
      "webhookSecret": "${BB_WEBHOOK_SECRET}",
      "username":      "my-user",
      "appPassword":   "${BB_APP_PASSWORD}"
    }
  },
  "repositories": [
    "my-workspace/backend",
    "my-workspace/frontend"
  ]
}
```

## Full Data Center Config Example

```json
{
  "plugin": {
    "providerId": "bitbucket",
    "properties": {
      "variant":       "datacenter",
      "baseUrl":       "https://bitbucket.example.com",
      "projectKey":    "BACKEND",
      "username":      "svc-indexer",
      "appPassword":   "${BB_DC_PASSWORD}",
      "webhookSecret": "${BB_DC_WEBHOOK_SECRET}"
    }
  },
  "repositories": [
    "BACKEND/api-service",
    "BACKEND/worker"
  ]
}
```

