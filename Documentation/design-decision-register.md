# CentralIndexer Design Decision Register

Captures all design decisions and rationale for the `ReviewToolCentralIndexer` module.

---

## Docker Deployment

### Decision
The CentralIndexer is packaged and deployed as a **Docker container**, with PostgreSQL
provided via a companion container defined in a `docker-compose.yml` file.

### Why

**Options Considered**:

1. **Plain fat JAR + systemd unit** ❌ (rejected as primary delivery)
   - Requires manual JVM installation and version management on the host
   - PostgreSQL setup, credentials, and connection configuration are left to the operator
   - No standard convention for where provider plugin JARs should be dropped
   - Acceptable for single-team, single-server deployments but does not scale to teams
     who want a reproducible setup

2. **Docker container + docker-compose** ✅ **CHOSEN**
   - One-command startup (`docker compose up`) with no manual prerequisites beyond Docker
   - PostgreSQL version and schema are managed alongside the application in source control
   - Provider plugin JARs are mounted into a well-defined volume (`/plugins`) inside the
     container, making the extension point explicit and portable
   - Credentials and provider tokens are injected via environment variables or Docker
     secrets — never baked into the image or committed to source control
   - Aligns naturally with the crash-recovery design: `restart: unless-stopped` in
     `docker-compose.yml` restarts the indexer after failure and the `reconcile()` path
     catches up missed events automatically

3. **Kubernetes / Helm** ❌ (deferred)
   - Appropriate only if horizontal scaling is introduced; out of scope for v1
   - Revisit if the single-node limits documented in `scalability.md` are reached

### Benefits
- **Reproducibility**: Identical environment across developer machines, CI, and production
- **Dependency isolation**: JVM version, PostgreSQL version, and plugin classpath are all
  pinned in a single source-controlled definition
- **Secrets hygiene**: Environment variable injection keeps tokens and passwords out of
  config files and source control
- **Operational fit**: The always-on, server-side nature of the indexer (it must be
  publicly reachable for webhooks) is the primary deployment model Docker targets
- **Plugin convention**: A named volume mount (`/plugins`) gives operators a clear and
  documented drop-in location for provider plugin JARs

### Acknowledgements
- Docker adds a layer of operational knowledge required of the operator; teams that
  cannot run Docker may fall back to the fat JAR + systemd approach
- Horizontal scaling is explicitly out of scope for v1; see `scalability.md` for the
  conditions under which it would be reconsidered

### Date Decided
2026-05-19

### Status
**ACTIVE** — Dockerfile and `docker-compose.yml` to be authored before first release

---

## Per-Repository `SubmissionPublisher`

### Decision
The SSE broadcaster uses a **`ConcurrentHashMap<String, SubmissionPublisher<ReviewEvent>>`**
keyed by repository name rather than a single global publisher.

### Why
A single global publisher delivers every event to every connected SSE client regardless
of which repository they are watching. At 10,000 repositories and 10,000 clients, every
event triggers 10,000 wakeups with a 0.01% useful hit rate — see `scalability.md` for
the full analysis.

A per-repository publisher means only the clients watching the relevant repository are
woken. The publisher for a repository is created lazily on first subscriber and removed
when the last subscriber disconnects.

### Implications
- The `pg_notify` LISTEN thread reads the repository from the incoming notification and
  looks up (or skips) the corresponding publisher rather than calling a single global one
- The SSE connection handler registers the client's subscriber with the repository-specific
  publisher, not a global one
- Publisher lifecycle: created on first `subscribe()`, removed on last `onComplete()` /
  `onError()` to avoid unbounded map growth

### Date Decided
2026-05-19

### Status
**ACTIVE** — Required before v1; shapes the core broadcaster implementation

---

## Configuration Format — JSON (Gson)

### Decision
Application configuration is stored in a **JSON file** (`config.json`), parsed using
**Gson** (`com.google.code.gson:gson`). Gson is also used to serialise `ReviewEvent`
objects into the `pg_notify` payload.

### Why

| Option | Verdict |
|---|---|
| YAML | Requires SnakeYAML; not an allowable dependency |
| `.properties` | Zero deps but verbose for nested structures (database, per-repository blocks) |
| JSON (manual parsing) | No deps but brittle and high maintenance for nested config |
| JSON (`org.json`) | Single JAR but manual tree traversal — more verbose than needed |
| JSON (Gson) | Single JAR, no transitive deps, direct Java object mapping, already allowable ✅ |
| JSON (Jackson) | Heavier than needed; more than one JAR |

Gson is one of only two allowable third-party libraries (alongside the PostgreSQL JDBC
driver). Using it for both config deserialisation and event serialisation keeps JSON
handling consistent throughout the codebase with a single well-understood library.

### Configuration location
Resolved in priority order:
1. System property `cri.config` (absolute path)
2. Environment variable `CRI_CONFIG`
3. `./config.json` relative to the working directory

### Date Decided
2026-05-19

### Status
**ACTIVE**

---

## SSE Client Authentication — Shared Bearer Token

### Decision
`GET /events/stream` is protected by a **configurable shared bearer token**. Clients
must include `Authorization: Bearer <token>` on every SSE request. Authentication can
be **disabled entirely** via config for deployments where network-level trust (VPN,
private subnet) is sufficient.

### Why

| Option | Verdict |
|---|---|
| No auth | Acceptable on private networks; too risky as a default |
| Shared bearer token | Matches the webhook secret trust model; zero client management overhead ✅ |
| Per-repository tokens | Useful for multi-tenant isolation; not a current requirement |
| Per-client issued tokens | Requires token issuance endpoint and storage; disproportionate complexity |

The indexer is an internal infrastructure tool. Its clients are Review Tool desktop
applications run by known team members. A shared bearer token is the right level of
protection for the target deployment model — it prevents casual access by anyone who
can reach the server while requiring no identity management infrastructure.

### Behaviour
- When `auth.enabled` is `true` (default): all endpoints except `GET /health` and
  `POST /webhooks/*` require a valid `Authorization: Bearer <token>` header. Requests
  without a header receive `401 Unauthorized`; requests with an invalid token receive
  `403 Forbidden`.
- When `auth.enabled` is `false`: `GET /events/stream` and `GET /events` are open to
  any client that can reach the server. Suitable for air-gapped or fully private network
  deployments.
- Webhook endpoints (`POST /webhooks/*`) are **never** protected by this token — they
  use the per-provider webhook secret for payload verification.
- The health endpoint (`GET /health`) is **always** unauthenticated.

### Configuration
```json
{
  "auth": {
    "enabled": true,
    "bearerToken": "${SSE_BEARER_TOKEN}"
  }
}
```

The token value should be injected via environment variable rather than stored in plain
text in the config file.

### Acknowledgements
- A single shared token means all clients with the token can subscribe to any
  repository's stream. Per-repository tokens should be reconsidered if multi-tenant
  isolation becomes a requirement.
- Token rotation requires a server restart in the current design.

### Date Decided
2026-05-19

### Status
**ACTIVE**

---

## Plugin Loading — Single Plugin via URLClassLoader + ServiceLoader

### Decision
Exactly **one** provider plugin is loaded per indexer instance. The plugin is discovered
via a `URLClassLoader` built from all JARs in the configured plugins directory, then
`ServiceLoader.load(ProviderPlugin.class, classLoader)`. If zero or more than one
implementation is found, the indexer logs an error and exits.

### Why
Supporting multiple simultaneous plugins (e.g. GitHub + Bitbucket on the same node)
introduces repository ownership conflicts: if two plugins both emit events for the same
repository name, sequence numbers and reconciliation become ambiguous. Running one plugin
per indexer instance eliminates this class of problem entirely.

Teams that serve multiple git providers deploy multiple indexer instances — one per
provider — each with its own PostgreSQL database and plugin JAR. This is operationally
simpler than trying to multiplex providers inside a single process.

### Specifics
- Plugins directory defaults to `./plugins`, overridable via system property `cri.plugins.dir`
- Two-phase lifecycle: `load()` discovers and validates exactly one plugin; `start()` configures and starts it
- `close()` on shutdown calls `ProviderPlugin.stop()` then closes the `URLClassLoader`
- ServiceLoader file: `META-INF/services/com.kalynx.centralindexer.spi.ProviderPlugin`

### Date Decided
2026-05-19

### Status
**ACTIVE**

---

## Webhook Error Handling — In-Memory Retry Queue

### Decision
When a webhook arrives and PostgreSQL is unreachable, the event is held in a bounded
**in-memory retry queue** and retried with exponential back-off. If the queue is full
or the maximum retry duration is exceeded, a `503 Service Unavailable` is returned to
the provider.

### Why
Most providers (GitHub, Bitbucket, GitLab) retry failed webhook deliveries for 24–72
hours on a `503`. Returning `503` immediately is therefore safe for short outages.
However, returning `503` on every attempt during a brief PostgreSQL restart causes the
provider to start its own 24-hour retry clock unnecessarily.

An in-memory queue absorbs transient PostgreSQL blips (seconds to low minutes) and
returns `200 OK` to the provider once the event is durably persisted, without
requiring the provider to re-deliver.

### Queue bounds
- Maximum queue depth: 1,000 events (configurable)
- Maximum retry duration: 5 minutes (configurable)
- Back-off: 1s, 2s, 4s, 8s … capped at 30s
- On queue full or timeout exceeded: return `503` and discard; provider retry handles the rest

### Date Decided
2026-05-19

### Status
**ACTIVE**

---

## Testing Strategy — Unit + Integration (Custom Docker Container)

### Decision
- **Unit tests** cover all business logic in isolation using mocks (Mockito). These are
  the primary fast-feedback tests and run on every build.
- **Integration tests** spin up a real PostgreSQL instance using a **custom
  `PostgresTestContainer`** class backed by the Docker CLI — no Testcontainers library.

### Why

| Option | Verdict |
|---|---|
| Mocks only | Cannot meaningfully test SQL behaviour, `pg_notify` delivery, or SSE sequencing |
| Testcontainers library | Solves the problem but adds ~8 transitive JARs; contradicts the no-frameworks philosophy |
| Custom `PostgresTestContainer` | ~100 lines of `ProcessBuilder` code; zero additional dependencies; Docker is already required for deployment ✅ |

The Testcontainers library is a convenience wrapper around the Docker CLI. For a single
container type (PostgreSQL) used in a single project, the wrapper is disproportionate.
The Docker CLI is already a hard requirement for running the deployment model, so its
presence on developer and CI machines is guaranteed.

### Implementation
`PostgresTestContainer` is a test-scoped `AutoCloseable` class that:

1. Runs `docker run -d --rm -p 0:5432 -e POSTGRES_PASSWORD=test -e POSTGRES_DB=indexer postgres:16`
   via `ProcessBuilder` and captures the container ID from stdout.
2. Runs `docker port <id> 5432` to discover the randomly assigned host port.
3. Polls a JDBC connection in a loop (250 ms interval, 30 second timeout) until
   PostgreSQL accepts connections — indicates the container is fully ready.
4. Exposes `getJdbcUrl()`, `getUser()`, `getPassword()` for test setup.
5. On `close()`, runs `docker stop <id>` to stop and auto-remove the container.

### Handling Docker Unavailability
If Docker is not available on the machine, `docker run` will fail. Integration tests
are annotated with a custom `@RequiresDocker` JUnit 5 extension that checks for Docker
availability and skips the test class gracefully rather than failing with a confusing
error.

### Acknowledgements
- Does not handle exotic Docker socket paths (Colima, Podman, remote Docker) without
  additional configuration. This is an acceptable limitation for the target developer
  environments (Docker Desktop on macOS/Windows, standard Docker on Linux CI).
- Image pulling on first run (`postgres:16`) may take time on a cold CI agent. Pull
  can be pre-warmed in CI pipeline setup steps.

### Date Decided
2026-05-19

### Status
**ACTIVE**

---

## HTTPS Support — Configurable

### Decision
HTTPS is supported but **configurable**. When enabled, the embedded HTTP server
terminates TLS directly using a configured keystore. When disabled, the server runs
plain HTTP and TLS termination is delegated to a reverse proxy (e.g. nginx in the
`docker-compose.yml`).

### Configuration
```json
{
  "server": {
    "port": 8765,
    "tls": {
      "enabled": true,
      "keystorePath": "/certs/keystore.p12",
      "keystorePassword": "${TLS_KEYSTORE_PASSWORD}",
      "keystoreType": "PKCS12"
    }
  }
}
```

When `tls.enabled` is `false` (or the `tls` block is absent), the server starts in
plain HTTP mode. This is the expected default for Docker deployments where nginx handles
TLS in front.

### Why
Operators who deploy the indexer directly on a public interface (no reverse proxy) need
TLS at the application layer. Operators using a compose stack with an nginx side-car do
not want to manage a keystore inside the application container. Making it configurable
supports both patterns.

### Date Decided
2026-05-19

### Status
**ACTIVE**

---

## Horizontal Scaling via PostgreSQL `pg_notify`

### Decision
The CentralIndexer supports **horizontal scaling by running multiple stateless nodes** behind
a load balancer. Cross-node fan-out uses PostgreSQL's built-in `pg_notify` / `LISTEN`
mechanism — no external message broker (Redis, Kafka, etc.) is required.

### Why

**The problem:** The in-process `SubmissionPublisher` fan-out only reaches SSE clients
connected to the same JVM instance. If a webhook lands on Node A, clients on Node B
would never receive the event with a purely in-memory design.

**Options Considered**:

1. **Redis Pub/Sub as the cross-node bus** ❌ (rejected)
   - Solves the problem cleanly, but adds a third infrastructure component that must be
     deployed, monitored, and kept highly available
   - Contradicts the design goal of keeping infrastructure to PostgreSQL only

2. **Kafka** ❌ (rejected)
   - Correct solution at very high write throughput, but the write path here is
     webhook-driven — throughput is inherently low
   - Operational complexity is disproportionate to the problem

3. **Sticky load balancing** ❌ (rejected)
   - Routes each SSE client to a fixed node so it only needs to hear local events
   - Breaks the crash-recovery model: if a node dies, all its sticky clients must
     reconnect and a new node must replay their history from PostgreSQL anyway
   - Provides no real isolation benefit and complicates load balancer configuration

4. **PostgreSQL `pg_notify` as the cross-node bus** ✅ **CHOSEN**
   - `pg_notify` is a broadcast mechanism already present in PostgreSQL
   - When any node inserts an event and calls `pg_notify('events', payload)`, PostgreSQL
     wakes **every active `LISTEN` connection** — one per node
   - Each node fans the event out to its own locally connected `SubmissionPublisher`
     subscribers
   - No inter-node communication, no additional infrastructure, no configuration changes
     between single-node and multi-node deployments

### How It Works

```
Webhook → Node A → INSERT + pg_notify
                         │
              PostgreSQL broadcasts
              to ALL LISTEN connections
                    │           │
               Node A        Node B
            LISTEN thread  LISTEN thread
                 │               │
          SubmissionPublisher  SubmissionPublisher
                 │               │
          local SSE clients   local SSE clients
```

### Deduplication
Multiple nodes may receive the same webhook delivery simultaneously. Providers deliver a
unique delivery ID per webhook call (e.g. `X-GitHub-Delivery`). The `events` table has a
partial unique index on `(repository, delivery_id) WHERE delivery_id IS NOT NULL`. When
two nodes race to insert the same delivery, exactly one succeeds; the other receives a
unique constraint violation and silently discards the duplicate without emitting an event.

`ReviewEvent` carries a `deliveryId` field so plugins can forward the provider ID through
to the persistence layer.

### Sequence Number Safety
`sequence_no` is assigned within the same transaction as the event insert, using a
`SELECT last_sequence_no FROM repository_state WHERE repository = ? FOR UPDATE` row
lock followed by an increment. At webhook frequency (< 100/second across all repos)
this lock is negligibly contended and avoids the operational complexity of maintaining
per-repository PostgreSQL sequences.

### Scaling Limits
- PostgreSQL `NOTIFY` payloads are limited to 8,000 bytes. The notification carries the
  full `ReviewEvent` serialised as JSON (~300 bytes) — no additional DB query is needed
  in the LISTEN thread. The 8,000-byte limit is never approached.
- `pg_notify` is not designed for thousands of `LISTEN` connections. The correct level of
  LISTEN contention is **one connection per node**, not one per client — which is exactly
  what this design does.
- At very high event rates (>1,000 events/second) PostgreSQL notification throughput could
  become a bottleneck; at that point migrating to a dedicated broker warrants
  re-evaluation.

### Benefits
- **No new infrastructure** — PostgreSQL is already required; this adds zero operational
  overhead
- **No sticky sessions** — any node can serve any client; load balancer configuration
  is trivially round-robin
- **Uniform single/multi-node code path** — the application code is identical whether
  one or ten nodes are running; scaling is purely an operational concern
- **Crash safety** — if a node dies its LISTEN connection is dropped, PostgreSQL cleans
  up automatically, and the replacement node re-establishes LISTEN on startup

### Acknowledgements
- Requires PostgreSQL as shared infrastructure; cannot use an embedded or file-based
  database in a multi-node deployment
- Nodes must be able to reach the same PostgreSQL instance; network partitions between
  a node and PostgreSQL will prevent that node's clients from receiving live events
  (they will continue to be served from replay on reconnect)

### Date Decided
2026-05-19

### Status
**DEFERRED** — The `pg_notify` / `LISTEN` mechanism means no code changes would be
required to go multi-node; the decision is simply that it is not needed yet.
See `scalability.md` for the triggers that would prompt revisiting this.

---

## Dynamic Repository Registration

### Decision
The indexer does **not** require a static list of repositories in `config.json`. A
repository is implicitly registered the first time a `ReviewEvent` for it passes through
`EventSink`. The core upserts the `repository_state` row on demand. The per-repository
`SubmissionPublisher` entry is a separate concern — it is created lazily on first SSE
client subscription, not on event arrival.

### Why

| Option | Verdict |
|---|---|
| Static list in config | Requires a server restart to add a new repository; adds configuration burden for large repo sets |
| Dynamic on first event | Zero configuration overhead; new repos become visible to SSE clients immediately ✅ |

The indexer's primary input is webhooks — the provider decides when events arrive, not
the operator. Requiring prior registration would mean that a new repository's events are
silently dropped until config is updated and the server restarted. Dynamic registration
eliminates this class of misconfiguration entirely.

### Implications
- `EventSink.submit()` must upsert into `repository_state` (`INSERT ... ON CONFLICT DO NOTHING`)
  before assigning `sequence_no`, ensuring the row exists before the `FOR UPDATE` lock.
- The `SubmissionPublisher` map entry is created lazily on first `subscribe()` as before;
  the repository registration itself does not create it.
- At startup, `reconcile()` is called on the single loaded plugin for every repository
  present in `repository_state` — no routing logic required.

### Acknowledgements
- A misconfigured plugin that emits events for unexpected repository names will create
  `repository_state` rows silently. Operators should monitor the repository list if
  correctness matters.

### Date Decided
2026-05-19

### Status
**ACTIVE** — Shapes the `EventSink` implementation and startup reconciliation loop.

---

## Event Type Consolidation — Drop `REVIEW_STATUS_CHANGED`

### Decision
`REVIEW_STATUS_CHANGED` is removed from the `EventType` enum. Status changes
(e.g. `OPEN → APPROVED`) are represented as `REVIEW_UPDATED`.

### Why
`REVIEW_STATUS_CHANGED` is semantically a subset of a review update. Having a separate
type creates two problems:

1. **Ambiguity for plugin authors** — it is unclear whether approving a review emits
   `REVIEW_UPDATED`, `REVIEW_STATUS_CHANGED`, or both.
2. **Unnecessary client complexity** — clients must handle an extra event type that
   triggers the same action (refresh the review item) as `REVIEW_UPDATED`.

A single `REVIEW_UPDATED` type covers all field-level changes to a review, including
status. If a client needs to distinguish the nature of the change it reads the `payload`
field, which is plugin-authored and provider-specific.

### Date Decided
2026-05-19

### Status
**ACTIVE** — `REVIEW_STATUS_CHANGED` removed from `EventType.java`. Plugin authors must
use `REVIEW_UPDATED` for all review-level field changes including status transitions.


















