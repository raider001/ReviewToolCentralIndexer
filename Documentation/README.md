# ReviewToolCentralIndexer

## Overview

The `ReviewToolCentralIndexer` is a standalone server-side component that acts as the bridge between hosted git providers (GitHub, Bitbucket, GitLab, etc.) and the Review Tool desktop clients. It ingests change events from provider plugins, persists them, and streams relevant updates to connected clients in real time using Server-Sent Events (SSE).

The indexer solves the polling limitation of the desktop client: instead of every client individually polling the remote git server on a timer, the indexer receives a single webhook notification, stores the event, and fans it out to all connected clients instantly.

**Design constraints:**
- Scales to ~10,000 concurrent read clients on a single node; see `scalability.md` for theoretical limits.
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

## Core Responsibilities

### 1. Event Ingestion — Plugin-Based

All provider-specific ingestion logic is implemented as a **`ProviderPlugin`**. Exactly one plugin is loaded per indexer instance — discovered via Java's `ServiceLoader` from JARs placed in the configured plugins directory. If zero or more than one implementation is found, the indexer exits with an error.

Each plugin:
- Declares which provider it handles (e.g. `"github"`, `"bitbucket"`).
- Registers one or more **webhook HTTP handlers** via `WebhookRouter` using any path suffix it chooses.
- Optionally starts a **background poller** as a fallback for providers without webhook support.
- Translates the provider payload into the canonical `ReviewEvent` type and submits it via `EventSink`.

**Repository registration is dynamic.** The indexer core does not require a static list of repositories. A repository is implicitly registered the first time a `ReviewEvent` for it passes through `EventSink` — the core upserts the `repository_state` row on demand. The per-repository `SubmissionPublisher` entry is created separately, only when the first SSE client subscribes to that repository. This means new repositories become visible to clients the moment their first event arrives, with no server restart or config change required.

The indexer core has no knowledge of any specific git provider.

### 2. Event Store (PostgreSQL)

- Append-only `events` table indexed by `(repository, sequence_no)`.
- Each row has a per-repository monotonically increasing `sequence_no` and a UTC `timestamp`.
- `sequence_no` is the cursor clients use to resume after disconnect — equivalent to a Kafka consumer offset.
- On insert, `pg_notify` is called to wake the internal LISTEN thread.
- A scheduled job prunes `events` rows older than the configured retention window. The `repository_state` table is **never pruned** — it holds the high-water mark (`last_sequence_no`, `last_event_time`) needed by `reconcile()` at startup and by clients for cursor continuity.
- Table partitioning by repository is **not used** — at 10,000+ repositories PostgreSQL degrades beyond ~1,000 list partitions; the composite index is the correct strategy.

```sql
CREATE TABLE events (
    id            BIGSERIAL PRIMARY KEY,
    sequence_no   BIGINT        NOT NULL,
    repository    TEXT          NOT NULL,
    event_type    TEXT          NOT NULL,
    review_id     TEXT,
    actor_user    TEXT,
    payload       JSONB,
    timestamp     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    delivery_id   TEXT
);

CREATE UNIQUE INDEX idx_events_repo_seq        ON events (repository, sequence_no);
CREATE UNIQUE INDEX idx_events_delivery_id     ON events (repository, delivery_id) WHERE delivery_id IS NOT NULL;
CREATE INDEX        idx_events_timestamp       ON events (timestamp);

CREATE TABLE repository_state (
    repository          TEXT        PRIMARY KEY,
    last_sequence_no    BIGINT      NOT NULL DEFAULT 0,
    last_event_time     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`sequence_no` is assigned transactionally: the insert and the `last_sequence_no`
increment happen in the same transaction using a `SELECT ... FOR UPDATE` row lock on
the `repository_state` row. At low webhook frequency (< 100/second across all repos)
this is negligibly contended and avoids the complexity of per-repository PostgreSQL
sequences.

At startup, the core reads all `repository_state` rows and calls
`reconcile(repository, since)` on the single loaded plugin for each row.

Both tables are created with `CREATE TABLE IF NOT EXISTS` on first startup; no
migration framework is required for v1.

### 3. SSE Broadcaster

- Exposes `GET /events/stream?repository=<name>&since=<sequenceNo>`.
- On connect: replays all stored events with `sequence_no > since` for the given repository, then transitions to live streaming via the `SubmissionPublisher` subscription.
- Clients pass `Last-Event-ID` on reconnect; the server uses that value as `since`.
- Handles slow consumers with a bounded per-client buffer — a client that cannot keep up is dropped rather than back-pressuring the publisher.

### 4. Crash Recovery and Gap Reconciliation

On startup the indexer performs the following sequence before accepting connections:

1. **Resume from last stored state** — the last `sequence_no` and `timestamp` per repository are read from PostgreSQL. No events stored before the crash are lost.
2. **Plugin reconciliation** — the core calls `ProviderPlugin.reconcile(repository, since)` on the single loaded plugin for every repository present in `repository_state`, passing the timestamp of the last stored event. Reconcile calls are parallelised with bounded concurrency (configured via `reconcileConcurrency`). If the provider API is unreachable, reconciliation for that repository is skipped with a warning after a configurable timeout (default 10 seconds) — the indexer proceeds to accept connections rather than blocking startup indefinitely. Missed events in this case will arrive via the provider's webhook retry window.
3. **Webhook retry window** — most providers (GitHub, Bitbucket) retry failed webhook deliveries for 24–72 hours. If the indexer recovers within that window, retried webhooks arrive automatically after reconciliation completes. The plugin must deduplicate on a provider-supplied delivery ID to avoid double-inserting.
4. **LISTEN re-establishment** — the single `LISTEN` connection is re-created and the per-repository `SubmissionPublisher` map is cleared before the HTTP server opens for client connections.

The `last_event_time` per repository is stored in a dedicated `repository_state` table so it survives restarts and is always consistent with the event log.

### 5. Client History Preservation

- All events are retained for a configurable window (default: 7 days).
- A client reconnecting within the window receives all missed events before live ones.
- A client reconnecting outside the window receives a `410 Gone` response indicating it must perform a full refresh.

---

## Provider Plugin SPI

### `ProviderPlugin`
```
providerId()                              → identifies the provider; validated against config.plugin.providerId on startup
start(ProviderConfig, EventSink, Router)  → register webhook handler, start optional poller
reconcile(repository, since)              → called at startup per repository in repository_state;
                                            backfill any events missed during downtime
stop()                                    → clean up pollers and resources
```

### `EventSink`
```
submit(ReviewEvent)  → hands a normalised event to the indexer core for storage and broadcast
```

### `WebhookRouter`
```
registerPost(pathSuffix, handler)  → registers POST /webhooks/{pathSuffix} on the HTTP server
```

---

## Event Model

| Field        | Type      | Description                                                |
|--------------|-----------|------------------------------------------------------------|
| `sequenceNo` | `long`    | Monotonically increasing, scoped per repository, assigned transactionally via `repository_state` |
| `timestamp`  | `Instant` | UTC time the event occurred on the provider side (not the indexer ingestion time)  |
| `repository` | `String`  | Canonical repository identifier in the form `owner/repo`                           |
| `eventType`  | `enum`    | `REVIEW_CREATED`, `REVIEW_UPDATED` (includes status changes), `REVIEW_CLOSED`, `REVIEW_COMMENT_ADDED`, `REVIEW_COMMENT_UPDATED`, `BRANCH_UPDATED`, `BRANCH_DELETED` |
| `reviewId`   | `String`  | Affected review identifier; `null` for branch-only events                          |
| `actorUser`  | `String`  | User who triggered the change (if available from provider)                         |
| `deliveryId` | `String`  | Provider-supplied webhook delivery ID used for deduplication; nullable for poll-sourced events |
| `payload`    | `Map<String, String>` | Key-value pairs carrying event-specific detail (e.g. `branchName`, `commitSha`, `commentId`); empty map when no extra data |

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/webhooks/{suffix}` | Webhook secret (per-provider) | Delegated to the matching registered `ProviderPlugin`. The path suffix is freely chosen by the plugin when it calls `WebhookRouter.registerPost(pathSuffix, handler)` — the indexer core imposes no naming convention. |
| `GET`  | `/events/stream`     | Bearer token (if enabled) | SSE stream — query params `repository` (required), `since` (optional sequence number) |
| `GET`  | `/events`            | Bearer token (if enabled) | Paginated event history — query params `repository` (required), `since`, `limit` (default 100) |
| `GET`  | `/health`            | None | Returns `200 OK` with JSON `{"status":"UP","db":"UP"}`. DB check uses a lightweight `SELECT 1`. |

### SSE stream format

```
id: 42
event: REVIEW_UPDATED
data: {"sequenceNo":42,"repository":"owner/backend","reviewId":"...","eventType":"REVIEW_UPDATED","actorUser":"alice","timestamp":"2026-05-19T10:30:00Z","payload":{}}

```

The `id` field is the sequence number. Clients pass `Last-Event-ID` on reconnect.
If the `since` value is older than the retention window the server responds with
`410 Gone` — the client must discard its local state and perform a full git notes
refresh before reconnecting with `since=0`.

### `GET /events` response format

```json
{
  "events": [
    {
      "sequenceNo": 38,
      "repository": "owner/backend",
      "eventType": "REVIEW_UPDATED",
      "reviewId": "abc-123",
      "actorUser": "alice",
      "timestamp": "2026-05-19T10:00:00Z",
      "payload": { "field": "value" },
      "deliveryId": "abc-delivery-id"
    }
  ],
  "nextSince": 38
}
```

`nextSince` is the `sequenceNo` of the last event returned. Clients pass this as `since`
on the next request to page forward. An empty `events` array means the client is
caught up. The `limit` parameter (default 100) caps how many events are returned
per response.

### Integration with the Review Tool Client

1. Check at startup whether a central indexer URL is configured in settings.
2. If configured, open an SSE connection to `/events/stream?repository=<name>` with `Authorization: Bearer <token>` (if auth is enabled).
3. On receiving an SSE event, trigger a targeted `ReviewItemManager.refreshRepository` rather than a full reload.
4. Persist the last received `sequenceNo` (`Last-Event-ID`) locally so reconnections replay only missed events.
5. On `410 Gone`: clear the local `sequenceNo`, perform a full git notes scan, then reconnect with `since=0`.

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





























































