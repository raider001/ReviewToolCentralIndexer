# ReviewToolCentralIndexer

## Overview

The `ReviewToolCentralIndexer` is a standalone server-side component that acts as the bridge between hosted git providers (GitHub, Bitbucket, GitLab, etc.) and the Review Tool desktop clients. It ingests change events from provider plugins, persists them, and streams relevant updates to connected clients in real time using Server-Sent Events (SSE).

The indexer solves the polling limitation of the desktop client: instead of every client individually polling the remote git server on a timer, the indexer receives a single webhook notification, stores the event, and fans it out to all connected clients instantly.

**Design constraints:**
- Scales to ~10,000 concurrent read clients on a single node; 
- See [scalability analysis](scalability.md) for theoretical limits.
- Writes are low-frequency — only incoming webhooks ever write to the store.
- Clients are strictly read-only.
- **Allowable third-party libraries: PostgreSQL JDBC driver and Gson only.** All other functionality uses JDK built-ins.

---

## High-Level Architecture

```
 ┌──────────────────────┐     webhook POST      ┌──────────────────────────────────────────────┐
 │  GitHub / Bitbucket  │ ──────────────────>   │             CentralIndexer Server            │
 │  GitLab / Self-hosted│                       │                                              │
 └──────────────────────┘                       │  ┌────────────────────────────────────────┐  │
                                                │  │         ProviderPlugin (SPI)           │  │
                                                │  │  translates webhook → ReviewEvent      │  │
                                                │  └────────────────┬───────────────────────┘  │
                                                │                   │ EventSink.submit()       │
                                                │  ┌────────────────▼───────────────────────┐  │
                                                │  │   PostgreSQL Event Store               │  │
                                                │  │   INSERT event + pg_notify             │  │
                                                │  └──────┬─────────────────────────────────┘  │
                                                │         │ LISTEN (1 dedicated connection)    │
                                                │  ┌──────▼──────────────────────────────────┐ │
                                                │  │  ConcurrentHashMap<repo,                │ │
                                                │  │    SubmissionPublisher<ReviewEvent>>    │ │
                                                │  │  (per-repository fan-out, JDK Flow API) │ │
                                                │  └──────┬──────────────────────────────────┘ │
                                                └─────────┼────────────────────────────────────┘
                                                          │ SSE  (one virtual thread per client)
                                          ┌───────────────┼──────────────────────────┐
                                          │               │                          │
                                   ┌──────▼──────┐ ┌──────▼──────┐           ┌───────▼─────┐
                                   │  Client A   │ │  Client B   │           │  Client C   │
                                   │ (last: 41)  │ │ (last: 38)  │           │  (new: 0)   │
                                   └─────────────┘ └─────────────┘           └─────────────┘
```

---

## Concurrency Model

The server uses **Java 21+ Virtual Threads** (Project Loom) to handle thousands of concurrent SSE connections without a thread-per-OS-thread cost. Each SSE client is served by a virtual thread that blocks cheaply on an event queue — the JVM schedules these on a small pool of carrier threads.

### Why this scales to 10,000 clients

| Layer | Approach | Notes |
|---|---|---|
| SSE connections | 1 virtual thread per client | Virtual threads are JVM-managed; 10,000 costs kilobytes, not gigabytes |
| PostgreSQL connections | Bounded pool (~10–20) via `BlockingQueue<Connection>` | Virtual threads waiting for a connection block cheaply on the queue |
| Write path | 1–2 pool connections | Webhooks are rare events; write contention is near zero |
| Replay queries | Small slice of the pool | `SELECT WHERE sequence_no > ? AND repository = ?` on an indexed table |
| Live fan-out | 1 `LISTEN` connection → per-repository `SubmissionPublisher` map | Only clients watching the relevant repository are woken per event |

See `scalability.md` for a full analysis of single-node theoretical limits and the conditions under which they are reached.

### Event fan-out flow

1. A webhook arrives and the `ProviderPlugin` calls `EventSink.submit(event)`.
2. The core inserts the event into PostgreSQL and calls `pg_notify('events', <eventJson>)` where the payload is the full `ReviewEvent` serialised as JSON (~300 bytes, well within PostgreSQL's 8,000-byte notification limit).
3. A single dedicated virtual thread holds a permanent `LISTEN` connection. It wakes on `pg_notify`, deserialises the event from the notification payload, extracts the repository name, and calls `submit(event)` on the matching per-repository `SubmissionPublisher`. If no clients are currently subscribed to that repository the notification is silently discarded.
4. Only the SSE virtual threads subscribed to that repository receive the event and push it to their client.

No polling, no shared mutable state across repositories, no unnecessary wakeups.

---

## Core responsibilities (summary)

- Ingest provider webhooks via `ProviderPlugin` and normalise them into the indexer's internal form.
- Maintain a read-optimised review index (latest summary per review) used by `GET /reviews` and SSE.
- Track branch/head state and review→branch mappings to route updates accurately.
- Publish live changes to subscribed clients over SSE according to the client interface spec.
- On startup, reconcile repository state with the provider to repair gaps and ensure index consistency.

See the storage and interface documents for implementation details:
- [Storage design](storage.md)
- [Client interface specification](../../../Documentation/Design/Interfaces/Client-Interface.md)

---

## Persistence
Persitence will be implemented via PostgreSQL. The schema is designed for efficient writes and for the read patterns of the client API.

- See [storage design](storage.md) for details.


## Provider Plugin SPI

The Indexer will be designed to be extensible to support multiple git providers.

- See the [Plugin Design document](../../../Documentation/Design/plugin-design.md) for details on plugin lifecycle and registration.


---

## Event model & API

The event formats and client-facing REST/SSE API are defined in the central interface specification:

- See the [Client Interface specification](../../../Documentation/Design/Interfaces/Client-Interface.md).

---

## Dependencies

| Dependency | Purpose | Scope |
|---|---|---|
| `org.postgresql:postgresql` | PostgreSQL JDBC driver | Runtime |
| `com.google.code.gson:gson` | JSON serialisation — config parsing and `pg_notify` event payload | Runtime |
| `com.sun.net.httpserver.HttpServer` | Embedded HTTP server for webhooks + SSE | JDK built-in |
| `java.util.concurrent.Flow` | Per-repository `SubmissionPublisher` fan-out | JDK built-in |
| `java.util.concurrent.Executors` | Virtual thread executor | JDK 21+ built-in |
| `org.slf4j:slf4j-simple` | Logging | Runtime |
| `org.junit.jupiter:junit-jupiter` | Unit tests | Test |
| `org.mockito:mockito-core` | Unit test mocks | Test |
| Custom `PostgresTestContainer` | Integration test PostgreSQL via Docker CLI | Test (no external dep) |

No other third-party libraries are permitted. Any new dependency must be justified against this constraint.

### Plugin dependencies

Provider plugins are loaded in their own `URLClassLoader`. Any JARs placed in the
configured plugins directory are added to that classloader's classpath, so plugin authors
may bundle their own third-party dependencies (HTTP clients, JSON parsers, etc.) as
additional JARs in the same directory. Plugin dependencies are isolated from the indexer
core classpath and from each other.

---

## Startup Behaviour

### PostgreSQL unavailability

If PostgreSQL is not reachable when the indexer starts, the startup sequence fails
immediately with a logged error. The server does **not** spin-wait for the database to
become available. The intended recovery mechanism is Docker's `restart: unless-stopped`
policy — Docker will restart the container in a back-off loop until PostgreSQL is ready.
This keeps the startup code simple and delegates connection-retry orchestration to the
process supervisor.

---

## Configuration

```json
{
  "server": {
    "port": 8765,
    "tls": {
      "enabled": false,
      "keystorePath": "/certs/keystore.p12",
      "keystorePassword": "${TLS_KEYSTORE_PASSWORD}",
      "keystoreType": "PKCS12"
    }
  },

  "auth": {
    "enabled": true,
    "bearerToken": "${SSE_BEARER_TOKEN}"
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
    "retryQueue": {
      "maxDepth": 1000,
      "maxRetryMinutes": 5
    },
    "pruneIntervalHours": 6
  },

  "plugin": {
    "providerId": "github",
    "properties": {
      "apiToken": "${GITHUB_TOKEN}",
      "webhookSecret": "${GITHUB_WEBHOOK_SECRET}"
    }
  },

  "repositories": [
    "owner/backend",
    "owner/frontend"
  ]
}
```

Config values of the form `${ENV_VAR}` are substituted from environment variables at
startup. This keeps secrets out of the config file and source control.

The `plugin` block is passed directly to `ProviderPlugin.start()` as a `ProviderConfig`.
The `providerId` is validated against the loaded plugin's `providerId()` on startup — a
mismatch exits the process immediately. The `properties` map is provider-specific and
defined by the plugin author (API tokens, webhook secrets, base URLs, etc.).

The `repositories` list tells the plugin which repositories it is responsible for. It is
used during startup reconciliation and is also available to the plugin for any per-repo
polling configuration. Repositories not listed here can still receive events dynamically
from incoming webhooks, but they will not be reconciled at startup.

---

## Deployment Model

The indexer is deployed as a single Docker container via `docker-compose`, with PostgreSQL as a companion container. No load balancer, no clustering.

A single node comfortably serves the target workload. See `scalability.md` for where the limits are and when a more complex deployment would be warranted.

The indexer can be deployed:
- **On-premise** alongside a self-hosted git server, receiving webhooks over the internal network.
- **Cloud-hosted** as a small container, receiving webhooks from GitHub/Bitbucket over HTTPS.

For teams that do not want a central server, the Review Tool desktop client continues to operate in fully-serverless mode (direct git notes polling). The indexer is strictly opt-in.

---

## Running the Tests

See [`running-tests.md`](running-tests.md) for full instructions, prerequisites, and a description of every test class.

---

## Out of Scope (v1)

- Multi-tenant isolation (per-repository or per-client token granularity).
- Horizontal scaling / load balancing — single node is the target; see `scalability.md`.
- Direct write operations from clients — the indexer is notify-only from the client perspective.
- Desktop client integration — wiring `centralIndexerUrl` / `centralIndexerToken` into `AppSettings` and implementing the SSE client in the Review Tool desktop application is a separate workstream, not part of building the server-side indexer.
