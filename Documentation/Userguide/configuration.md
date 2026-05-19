# Configuration Reference

All configuration is loaded from a single JSON file (`config.json`). The file location is resolved at startup — see [Installation — Config File Location](installation.md#config-file-location).

## Environment Variable Substitution

Any value in `config.json` may contain `${ENV_VAR}` placeholders. They are replaced by the corresponding environment variable before the JSON is parsed.

```json
{
  "auth": {
    "bearerToken": "${BEARER_TOKEN}"
  },
  "database": {
    "password": "${DB_PASSWORD}"
  }
}
```

If a referenced variable is not set, startup fails with an `IllegalStateException`.

---

## Full Example

```json
{
  "server": {
    "port": 8765,
    "tls": {
      "enabled": false,
      "keystorePath": "/etc/central-indexer/keystore.p12",
      "keystorePassword": "${TLS_KEYSTORE_PASSWORD}",
      "keystoreType": "PKCS12"
    }
  },
  "auth": {
    "enabled": true,
    "bearerToken": "${BEARER_TOKEN}"
  },
  "database": {
    "url": "jdbc:postgresql://localhost:5432/indexer",
    "user": "indexer",
    "password": "${DB_PASSWORD}",
    "poolSize": 20
  },
  "indexer": {
    "retentionDays": 7,
    "pluginsDir": "./plugins",
    "reconcileConcurrency": 50,
    "reconcileTimeoutSeconds": 10,
    "pruneIntervalHours": 6,
    "retryQueue": {
      "maxDepth": 1000,
      "maxRetryMinutes": 5
    }
  },
  "plugin": {
    "providerId": "github",
    "properties": {
      "webhookSecret": "${WEBHOOK_SECRET}",
      "apiToken": "${GITHUB_API_TOKEN}"
    }
  },
  "repositories": [
    "my-org/repo-a",
    "my-org/repo-b"
  ]
}
```

---

## `server` Block

Controls the embedded HTTP server.

| Field | Type | Default | Description |
|---|---|---|---|
| `port` | integer | `8765` | TCP port to bind. |
| `tls` | object | _(absent)_ | Optional TLS settings — see below. If absent, plain HTTP is used. |

### `server.tls` Block

When `tls` is absent or `enabled` is `false`, the server uses plain HTTP.

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Set to `true` to enable HTTPS. |
| `keystorePath` | string | _(required when enabled)_ | Filesystem path to the PKCS12 or JKS keystore. |
| `keystorePassword` | string | _(required when enabled)_ | Password used to open the keystore. Use `${ENV_VAR}` to avoid hardcoding. |
| `keystoreType` | string | `"PKCS12"` | Keystore format. Accepted values: `"PKCS12"`, `"JKS"`. |

**Generating a self-signed keystore (PKCS12):**

```bash
keytool -genkeypair -alias indexer -keyalg RSA -keysize 2048 \
  -validity 365 -storetype PKCS12 \
  -keystore keystore.p12 -storepass changeit \
  -dname "CN=central-indexer,O=Example,C=US"
```

---

## `auth` Block

Guards the `/events` and `/events/stream` endpoints with a static Bearer token. The `/health` and `/webhooks/*` endpoints are never subject to this check.

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `true` | When `false`, the protected endpoints are open to any client. |
| `bearerToken` | string | _(required when enabled)_ | The token clients must supply as `Authorization: Bearer <token>`. |

**Client usage:**

```
GET /events?repository=my-org/repo-a&since=0
Authorization: Bearer my-secret-token
```

---

## `database` Block

PostgreSQL connection configuration.

| Field | Type | Default | Description |
|---|---|---|---|
| `url` | string | _(required)_ | JDBC connection URL, e.g. `jdbc:postgresql://localhost:5432/indexer`. |
| `user` | string | _(required)_ | Database username. |
| `password` | string | _(required)_ | Database password. Use `${ENV_VAR}` to avoid hardcoding. |
| `poolSize` | integer | `20` | Number of persistent JDBC connections to maintain. |

The indexer creates its schema automatically on startup using `CREATE TABLE IF NOT EXISTS`, so no separate migration step is needed for a fresh database.

---

## `indexer` Block

Controls core indexer behaviour: event retention, plugin loading, reconciliation, and pruning.

| Field | Type | Default | Description |
|---|---|---|---|
| `retentionDays` | integer | `7` | Number of days events are held before the pruning job deletes them. |
| `pluginsDir` | string | `"./plugins"` | Directory from which external provider plugin JARs are loaded. Overridable with the `-Dcri.plugins.dir` system property. |
| `reconcileConcurrency` | integer | `50` | Maximum number of repositories reconciled concurrently at startup. |
| `reconcileTimeoutSeconds` | integer | `10` | Per-repository timeout for reconciliation calls. Exceeded calls are interrupted. |
| `pruneIntervalHours` | integer | `6` | How often the background pruning job runs. |

### `indexer.retryQueue` Block

When PostgreSQL is temporarily unavailable, incoming webhook events are held in an in-memory queue and retried on the next successful database connection. If the queue is full, the webhook handler returns `503 Service Unavailable`.

| Field | Type | Default | Description |
|---|---|---|---|
| `maxDepth` | integer | `1000` | Maximum number of events held simultaneously in the retry queue. |
| `maxRetryMinutes` | integer | `5` | Events that have been retrying for longer than this limit are discarded with a warning log. |

---

## `plugin` Block

Selects and configures the provider plugin. See [Built-in Plugins](plugins/README.md) for full plugin setup guides.

| Field | Type | Default | Description |
|---|---|---|---|
| `providerId` | string | _(required)_ | Identifies which provider to use. Built-in values: `"github"`, `"bitbucket"`, `"gitlab"`. |
| `properties` | object | `{}` | Key-value map of provider-specific settings (API tokens, webhook secrets, etc.). |

**Plugin loading order:**

1. The indexer scans `indexer.pluginsDir` for JAR files that declare a `ProviderPlugin` SPI implementation. If any are found, the first one that matches `providerId` is used.
2. If no external JARs are found, the indexer falls back to the matching built-in plugin.
3. If neither is found, startup fails.

---

## `repositories` Array

A list of canonical repository identifiers that the selected plugin will reconcile on startup. Each entry is passed to the plugin's `reconcile()` method after startup to replay any events missed while the indexer was offline.

```json
"repositories": [
  "my-org/backend",
  "my-org/frontend"
]
```

Format depends on the provider:
- **GitHub / Bitbucket Cloud / GitLab**: `owner/repo` (e.g. `"my-org/my-repo"`)
- **Bitbucket Data Center**: `projectKey/repoSlug` (e.g. `"PROJ/my-repo"`)

