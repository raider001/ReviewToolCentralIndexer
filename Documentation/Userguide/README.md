# Central Indexer — User Guide

The Central Indexer is a lightweight, self-hosted event bus for the Serverless Review Tool. It receives push-event webhooks from your Git hosting provider, persists them in PostgreSQL, and fans the events out to connected review client applications over Server-Sent Events (SSE).

## Contents

| Section | Description |
|---|---|
| [Installation & Startup](installation.md) | Build, run standalone, run as a system service |
| [Configuration Reference](configuration.md) | All `config.json` fields and environment-variable substitution |
| [Built-in Plugins](plugins/README.md) | Overview of the built-in provider plugins and how to select one |
| → [GitHub Plugin](plugins/github.md) | Setup and configuration for GitHub |
| → [Bitbucket Plugin](plugins/bitbucket.md) | Setup and configuration for Bitbucket Cloud and Data Center |
| → [GitLab Plugin](plugins/gitlab.md) | Setup and configuration for GitLab.com and self-hosted GitLab |

## Architecture Overview

```
GitHub / Bitbucket / GitLab
          │  webhook push
          ▼
  POST /webhooks/{provider}
          │
  ┌───────┴────────────────────────────────────────┐
  │              Central Indexer                   │
  │                                                │
  │  Built-in Plugin ──► EventSink ──► PostgreSQL  │
  │                                        │       │
  │  GET /events           ◄───────────────┘       │
  │  GET /events/stream (SSE)                      │
  │  GET /health                                   │
  └────────────────────────────────────────────────┘
          │  SSE / polling
          ▼
  Review Tool Application (clients)
```

## Quick Start

1. Create `config.json` — see [Configuration Reference](configuration.md).
2. Start PostgreSQL 16+ and set `database.url` to your JDBC URL.
3. Run `java -jar central-indexer.jar`.
4. Register a webhook in your Git provider pointing to `http://<host>:8765/webhooks/<provider>`.

## HTTP Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/health` | None | Returns `{"status":"UP","db":"UP\|DOWN"}` |
| `GET` | `/events` | Bearer | Paginated event history |
| `GET` | `/events/stream` | Bearer | Live SSE stream |
| `POST` | `/webhooks/github` | Webhook signature | GitHub push events |
| `POST` | `/webhooks/bitbucket` | Webhook signature | Bitbucket push events |
| `POST` | `/webhooks/gitlab` | Webhook token | GitLab push events |

