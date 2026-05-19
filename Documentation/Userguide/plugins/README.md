# Built-in Plugins

The Central Indexer ships with three provider plugins. They are compiled into the main JAR and require no additional files to use.

## Selecting a Plugin

Set `plugin.providerId` in `config.json` to the desired provider:

| `providerId` | Provider |
|---|---|
| `"github"` | GitHub (github.com) |
| `"bitbucket"` | Bitbucket Cloud or Bitbucket Data Center |
| `"gitlab"` | GitLab.com or self-hosted GitLab |

```json
"plugin": {
  "providerId": "github",
  "properties": { }
}
```

## External Plugin Override

If you place a provider plugin JAR in the `pluginsDir` directory, it will take precedence over the built-in with the same `providerId`. Built-in plugins are only used when no external JARs are present.

## Plugin Guides

- [GitHub Plugin](github.md)
- [Bitbucket Plugin](bitbucket.md)
- [GitLab Plugin](gitlab.md)

## How Reconciliation Works

When the indexer starts (or restarts), it calls each built-in plugin's `reconcile()` method for every repository listed in the `repositories` array. This catches any push events that were delivered to the Git provider while the indexer was offline.

Reconciliation runs concurrently up to `indexer.reconcileConcurrency` repositories at a time and times out per repository after `indexer.reconcileTimeoutSeconds` seconds.

## Webhook URL Summary

| Plugin | Webhook URL Path |
|---|---|
| GitHub | `POST /webhooks/github` |
| Bitbucket | `POST /webhooks/bitbucket` |
| GitLab | `POST /webhooks/gitlab` |

The full URL to register in your provider's webhook settings is:

```
http(s)://<your-indexer-host>:<port>/webhooks/<provider>
```

For example: `https://indexer.example.com:8765/webhooks/github`

