# CentralIndexer — Implementation Roadmap

Each milestone produces a vertical slice of working, tested functionality.
The gate criteria for each milestone must all pass before work on the next begins.
Tests labelled **unit** use Mockito and run on every build with no external dependencies.
Tests labelled **integration** use `PostgresTestContainer` + Docker and are annotated
`@RequiresDocker` — they are skipped gracefully when Docker is unavailable.

---

## Milestone 0 — Test Infrastructure

> **Status: ✅ Complete**

**Goal:** Build the two shared test utilities that all subsequent integration tests depend
on. Nothing in M1–M10 can run integration tests without this milestone complete.

### What Must Work

| # | Behaviour |
|---|-----------|
| 0.1 | `PostgresTestContainer` starts a PostgreSQL 16 container via `ProcessBuilder` running `docker run -d --rm -p 0:5432 -e POSTGRES_PASSWORD=test -e POSTGRES_DB=indexer postgres:16`. The container ID is captured from stdout. |
| 0.2 | `PostgresTestContainer` discovers the randomly assigned host port by running `docker port <id> 5432` and parsing the output. |
| 0.3 | `PostgresTestContainer` polls a JDBC connection every 250 ms (up to a 30 second timeout) until PostgreSQL accepts connections before returning from the constructor. |
| 0.4 | `PostgresTestContainer` exposes `getJdbcUrl()`, `getUser()`, and `getPassword()` for test setup code. |
| 0.5 | `PostgresTestContainer.close()` runs `docker stop <id>`, which auto-removes the container (due to `--rm`). |
| 0.6 | `RequiresDockerCondition` is a JUnit 5 `ExecutionCondition` that runs `docker info` via `ProcessBuilder`. If the command fails, the test class is skipped with a readable reason rather than failing. |
| 0.7 | The `@RequiresDocker` annotation is a JUnit 5 composed annotation (`@ExtendWith(RequiresDockerCondition.class)`) applicable to test classes. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `RequiresDockerConditionTest` | `enabledWhenDockerAvailable` | Condition returns `enabled` when `docker info` exits 0 |
| `RequiresDockerConditionTest` | `disabledWhenDockerUnavailable` | Condition returns `disabled` when `docker info` fails |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `PostgresTestContainerIT` | `containerStartsAndAcceptsConnections` | `SELECT 1` executes successfully using `getJdbcUrl()` |
| `PostgresTestContainerIT` | `closeStopsContainer` | After `close()`, the container ID no longer appears in `docker ps` output |

### Gate Criteria

- `PostgresTestContainer` and `@RequiresDocker` compile and pass their own tests.
- Both utilities live in `src/test/java` only — not on the production classpath.
- Running the integration test without Docker does not fail — it is skipped with a reason message.

---

## Milestone 1 — Foundation: Config & Database Bootstrap

> **Status: ✅ Complete** *(unit tests pass; integration tests require Docker)*

**Goal:** The application can read its configuration from `config.json` (with environment
variable substitution), connect to PostgreSQL, and create the schema from scratch.

### What Must Work

| # | Behaviour |
|---|-----------|
| 1.1 | `ConfigLoader` resolves the config file location in strict priority order: (1) system property `cri.config`, (2) environment variable `CRI_CONFIG`, (3) `./config.json`. The first match wins; later options are not checked even if the located file is invalid. |
| 1.2 | All `${ENV_VAR}` placeholders in the config file are substituted with live environment variable values before Gson deserialisation. A placeholder referencing a missing environment variable causes startup to fail with an `IllegalStateException` naming the missing variable. |
| 1.3 | A config file with syntactically invalid JSON causes startup to fail with a `ConfigLoadException` wrapping the Gson parse error. |
| 1.4 | `ConnectionPool` opens exactly `database.poolSize` JDBC connections on construction. `acquire()` blocks until a connection is available. `release(Connection)` returns a connection to the pool for reuse. |
| 1.5 | When PostgreSQL is unreachable during `ConnectionPool` construction, the constructor throws `DataSourceException` immediately — no retry loop, no sleep. |
| 1.6 | `DatabaseInitializer.init()` runs `CREATE TABLE IF NOT EXISTS` for both `events` and `repository_state` and creates all three indexes (`idx_events_repo_seq`, `idx_events_delivery_id`, `idx_events_timestamp`). Running against an already-initialised schema is a no-op. |
| 1.7 | `GsonFactory` produces a Gson instance that correctly serialises and deserialises `java.time.Instant` as ISO-8601 strings and `Map<String, String>` without data loss. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `ConfigLoaderTest` | `loadsFromSystemPropertyPath` | When `cri.config` is set, that path is read and values match file |
| `ConfigLoaderTest` | `systemPropertyTakesPriorityOverEnvVar` | When both `cri.config` and `CRI_CONFIG` are set, `cri.config` path wins |
| `ConfigLoaderTest` | `envVarTakesPriorityOverDefaultPath` | `CRI_CONFIG` used when `cri.config` is absent |
| `ConfigLoaderTest` | `substitutesEnvVars` | `${MY_VAR}` replaced with the injected env value before deserialisation |
| `ConfigLoaderTest` | `throwsOnMissingEnvVar` | `IllegalStateException` naming the variable when placeholder has no env entry |
| `ConfigLoaderTest` | `throwsOnInvalidJson` | `ConfigLoadException` wrapping parse error for non-JSON file content |
| `ConnectionPoolTest` | `poolSizeMatchesConfig` | `acquire()` succeeds `poolSize` times without blocking; `poolSize + 1` call blocks |
| `ConnectionPoolTest` | `releaseReturnsConnectionToPool` | Released connection re-acquired without a new JDBC connection opened |
| `GsonFactoryTest` | `instantRoundTrip` | `Instant.parse("2026-05-19T10:00:00Z")` serialises and deserialises to the same value |
| `GsonFactoryTest` | `mapStringStringRoundTrip` | `Map.of("k","v")` survives a Gson round-trip unchanged |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `ConnectionPoolIT` | `failsFastWhenDatabaseUnreachable` | `ConnectionPool` constructor throws `DataSourceException` when pointed at a non-existent host; returns within 5 seconds |
| `ConnectionPoolIT` | `acquireAndRelease` | Real connection executes `SELECT 1` and is returned cleanly |
| `DatabaseInitializerIT` | `createsTablesOnFreshDatabase` | After `init()`, both tables and all three indexes exist in `information_schema` |
| `DatabaseInitializerIT` | `idempotentOnExistingSchema` | Second `init()` call throws no exception |

### Gate Criteria

- All unit tests pass without a running database.
- `failsFastWhenDatabaseUnreachable` confirms fail-fast behaviour — not a slow JDBC timeout.
- All three indexes confirmed present in `createsTablesOnFreshDatabase`.

---

## Milestone 2 — Event Persistence

> **Status: ✅ Complete**

**Goal:** Events can be written to and read from PostgreSQL with correct
per-repository sequence number assignment, deduplication, and pruning.

### What Must Work

| # | Behaviour |
|---|-----------|
| 2.1 | `EventRepository.insert()` first upserts the `repository_state` row (`INSERT INTO repository_state ... ON CONFLICT DO NOTHING`) before acquiring the `SELECT ... FOR UPDATE` row lock, guaranteeing the lock target exists for the first event on a brand-new repository. |
| 2.2 | `sequence_no` is assigned transactionally: the `repository_state.last_sequence_no` counter is incremented and the `events` row is inserted in the same JDBC transaction under a `SELECT ... FOR UPDATE` row lock. Concurrent inserts for the same repository never produce duplicate sequence numbers. |
| 2.3 | After insert, `pg_notify('events', <eventJson>)` is called within the same transaction. The payload is the complete `ReviewEvent` serialised as JSON by `GsonFactory`. |
| 2.4 | `insert()` returns `Optional<ReviewEvent>` containing the stored event (with assigned `sequenceNo`) on success. |
| 2.5 | A second insert with the same non-null `repository` + `delivery_id` silently swallows the unique index violation and returns `Optional.empty()`. |
| 2.6 | Two inserts with `deliveryId = null` for the same repository both succeed (the partial index only covers non-null delivery IDs). |
| 2.7 | `EventRepository.queryEvents(repository, since, limit)` returns up to `limit` events with `sequence_no > since` ordered ascending by `sequence_no`. |
| 2.8 | `EventRepository.queryRepositoryStates()` returns one `RepositoryState` per row in `repository_state`. |
| 2.9 | `EventRepository.pruneOlderThan(days)` deletes `events` rows with `timestamp < now() - interval '<days> days'`. It never touches `repository_state`. |
| 2.10 | `EventRepository.hasEventAt(repository, sequenceNo)` returns `true` iff a row with that exact `sequence_no` for the given `repository` exists in `events`. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `EventRepositoryTest` | `insertReturnsStoredEventWithSequenceNo` | Returned Optional is present; `sequenceNo > 0` |
| `EventRepositoryTest` | `sequenceNoIsPerRepositoryMonotonic` | Two inserts same repo yield 1 then 2; separate repo also starts at 1 |
| `EventRepositoryTest` | `upsertRepositoryStateIssuedBeforeLock` | SQL execution order: `INSERT ... ON CONFLICT DO NOTHING` before `SELECT ... FOR UPDATE` |
| `EventRepositoryTest` | `duplicateDeliveryIdReturnsEmpty` | Second insert with same non-null `deliveryId` returns `Optional.empty()` |
| `EventRepositoryTest` | `nullDeliveryIdNotDeduplicated` | Two inserts with `deliveryId = null` both return non-empty Optionals |
| `EventRepositoryTest` | `queryEventsPaginates` | With 5 events stored, `queryEvents(repo, 2, 2)` returns events with `sequenceNo` 3 and 4 only |
| `EventRepositoryTest` | `pruneDeletesOldEventsOnly` | Events older than threshold deleted; newer events survive |
| `EventRepositoryTest` | `pruneNeverTouchesRepositoryState` | `repository_state` row count unchanged after prune |
| `EventRepositoryTest` | `hasEventAtReturnsTrueWhenPresent` | Returns `true` for an existing sequence number |
| `EventRepositoryTest` | `hasEventAtReturnsFalseWhenAbsent` | Returns `false` for a non-existent sequence number |

*(Unit tests mock the JDBC `Connection` using Mockito; SQL strings verified via `ArgumentCaptor` on `PreparedStatement`.)*

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `EventRepositoryIT` | `concurrentInsertsProduceUniqueSequenceNos` | 20 threads inserting to the same repository produce `sequenceNo` 1–20 with no gaps or duplicates |
| `EventRepositoryIT` | `pgNotifyPayloadMatchesInsertedEvent` | After `insert()`, a second JDBC `LISTEN events` connection receives a notification whose payload deserialises to the stored `ReviewEvent` |
| `EventRepositoryIT` | `duplicateDeliveryIdWritesOnlyOneRow` | Same `deliveryId` inserted twice; `SELECT COUNT(*)` returns 1 |
| `EventRepositoryIT` | `queryAfterPruneRespectsRetentionWindow` | Event with backdated timestamp absent after `pruneOlderThan()` |
| `EventRepositoryIT` | `hasEventAtReturnsFalseAfterPrune` | `hasEventAt()` returns `false` for the pruned sequence number |

### Gate Criteria

- Concurrent insert test passes with zero duplicate sequence numbers across 20 threads.
- `pgNotifyPayloadMatchesInsertedEvent` confirms payload received and round-trips cleanly.
- Duplicate `delivery_id` constraint confirmed to suppress second insert silently.

---

## Milestone 3 — Plugin System

> **Status: ✅ Complete**

**Goal:** The indexer can discover, load, validate, and start exactly one provider plugin
from the configured plugins directory. The plugin receives working `EventSink` and
`WebhookRouter` implementations.

### What Must Work

| # | Behaviour |
|---|-----------|
| 3.1 | `PluginLoader` resolves the plugins directory using: (1) system property `cri.plugins.dir`, (2) `indexer.pluginsDir` from config, (3) `./plugins` as the default. All JARs in the directory are added to a `URLClassLoader` whose parent is the application classloader. |
| 3.2 | `PluginLoader.load()` uses `ServiceLoader.load(ProviderPlugin.class, pluginClassLoader)` to discover implementations. Finding zero throws `PluginLoadException` identifying the searched directory. |
| 3.3 | Finding more than one implementation throws `PluginLoadException` listing each conflicting class name. |
| 3.4 | `plugin.providerId()` is compared to `config.plugin.providerId`. A mismatch throws `PluginLoadException` showing both values. |
| 3.5 | `PluginLoader.start()` calls `plugin.start(providerConfig, sink, router)` exactly once with a `ProviderConfig` built from `config.plugin` and `config.repositories`. |
| 3.6 | `PluginLoader.close()` calls `plugin.stop()` first, then always closes the `URLClassLoader` — even if `stop()` throws. |
| 3.7 | `EventSinkImpl.submit()` calls `EventRepository.insert()`. On `Optional.empty()` (duplicate), nothing is published. On a stored event, that event is passed to `PublisherRegistry.publish()`. |
| 3.8 | `WebhookRouterImpl.registerPost(suffix, handler)` stores the handler. `dispatch(suffix, headers, body)` invokes the matching handler and returns `true`; an unrecognised suffix returns `false`. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `PluginLoaderTest` | `throwsWhenNoPlugin` | `PluginLoadException` when service file lists zero implementations |
| `PluginLoaderTest` | `throwsWhenMultiplePlugins` | `PluginLoadException` listing both class names |
| `PluginLoaderTest` | `throwsOnProviderIdMismatch` | `PluginLoadException` showing both IDs |
| `PluginLoaderTest` | `pluginsDirSystemPropertyOverridesConfig` | Directory resolved from `cri.plugins.dir` wins over config value |
| `PluginLoaderTest` | `startCallsPluginStartExactlyOnce` | `verify(plugin, times(1)).start(any(), any(), any())` |
| `PluginLoaderTest` | `closeAlwaysClosesClassLoaderEvenWhenStopThrows` | `URLClassLoader.close()` called even when `plugin.stop()` throws |
| `EventSinkImplTest` | `submitPersistsAndPublishes` | `insert()` called; `PublisherRegistry.publish()` called with stored event |
| `EventSinkImplTest` | `duplicateSuppressedNothingPublished` | `Optional.empty()` from insert → `publish()` not called |
| `WebhookRouterImplTest` | `registeredHandlerInvoked` | Handler for `"push"` called with correct headers and body |
| `WebhookRouterImplTest` | `unknownSuffixReturnsFalse` | `dispatch()` returns `false` for unregistered suffix |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `PluginLoaderIT` | `loadsRealPluginFromJarFile` | A minimal `ProviderPlugin` compiled into a temp JAR is discovered, `providerId()` validated, and `start()` called |
| `EventSinkImplIT` | `submitInsertsRowAndAssignsSequenceNo` | After `submit()`, `SELECT sequence_no FROM events WHERE repository = ?` returns 1 |

### Gate Criteria

- `loadsRealPluginFromJarFile` uses an actual dynamically compiled JAR.
- `closeAlwaysClosesClassLoaderEvenWhenStopThrows` confirms `URLClassLoader` is always closed.
- Duplicate delivery ID suppression confirmed end-to-end through `EventSinkImpl`.

---

## Milestone 4 — HTTP Server, Routing, Auth & Health

> **Status: ✅ Complete**

**Goal:** The embedded HTTP server is running, routes webhook requests to the registered
plugin handler, enforces Bearer token auth on the correct endpoints, and responds correctly
to health checks.

### What Must Work

| # | Behaviour |
|---|-----------|
| 4.1 | `IndexerHttpServer` starts `com.sun.net.httpserver.HttpServer` on `server.port` with `Executors.newVirtualThreadPerTaskExecutor()` as the executor. |
| 4.2 | `POST /webhooks/{suffix}` strips the `/webhooks/` prefix, calls `WebhookRouterImpl.dispatch()`. On match returns `200 OK`; on no match returns `404 Not Found`. |
| 4.3 | `GET /health` returns `200 OK` with `Content-Type: application/json` and body `{"status":"UP","db":"UP"}` when `SELECT 1` succeeds. |
| 4.4 | `GET /health` returns `200 OK` with `{"status":"UP","db":"DOWN"}` when `SELECT 1` throws `SQLException`. |
| 4.5 | When `auth.enabled` is `true`: requests to `GET /events/stream` and `GET /events` without an `Authorization` header return `401 Unauthorized`. |
| 4.6 | When `auth.enabled` is `true`: `Authorization: Bearer <wrong-token>` returns `403 Forbidden`. |
| 4.7 | When `auth.enabled` is `true`: the correct Bearer token allows the request to be handled normally. |
| 4.8 | When `auth.enabled` is `false`: `GET /events/stream` and `GET /events` are accessible without any `Authorization` header. |
| 4.9 | `POST /webhooks/*` and `GET /health` bypass auth entirely regardless of the `auth.enabled` setting. |
| 4.10 | When `server.tls.enabled` is `false` (or the `tls` block is absent), the server binds plain `HttpServer`. When `true`, an `HttpsServer` is used with the configured keystore. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `AuthFilterTest` | `missingHeaderReturns401` | Response code 401; downstream handler not called |
| `AuthFilterTest` | `wrongTokenReturns403` | Response code 403; downstream handler not called |
| `AuthFilterTest` | `correctTokenAllowsThrough` | Downstream handler invoked |
| `AuthFilterTest` | `authDisabledAllowsAllRequests` | When `auth.enabled=false`, handler always invoked regardless of header |
| `AuthFilterTest` | `healthAlwaysBypassesAuth` | `/health` handler invoked regardless of auth config and headers |
| `AuthFilterTest` | `webhooksAlwaysBypassesAuth` | `/webhooks/push` handler invoked regardless of auth config and headers |
| `HealthHandlerTest` | `upResponseWhenDbPingSucceeds` | JSON body `{"status":"UP","db":"UP"}` and HTTP 200 |
| `HealthHandlerTest` | `dbDownWhenPingThrowsSqlException` | JSON body `{"status":"UP","db":"DOWN"}` and HTTP 200 |
| `WebhookDispatcherTest` | `routesToRegisteredHandler` | Handler invoked with correct raw body and headers |
| `WebhookDispatcherTest` | `returns404ForUnknownSuffix` | HTTP 404 |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `HttpServerIT` | `healthEndpointReturns200` | Real `HttpURLConnection` GET to `/health` returns 200 with valid JSON |
| `HttpServerIT` | `webhookDispatchedToPlugin` | POST to `/webhooks/push` body received by registered in-test handler; 200 returned |
| `HttpServerIT` | `authRejects401WithNoToken` | GET `/events` returns 401 when auth enabled and no header |
| `HttpServerIT` | `authRejects403WithWrongToken` | GET `/events` returns 403 when auth enabled and wrong token |
| `HttpServerIT` | `authAcceptsCorrectToken` | GET `/events` returns non-401/403 when correct token sent |
| `HttpServerIT` | `authDisabledAllowsEventsWithoutToken` | GET `/events` returns non-401/403 when auth disabled and no header |

### Gate Criteria

- Server starts and stops cleanly in each integration test (port released after each test).
- All auth scenarios (no header, wrong token, correct token, auth disabled) confirmed live over TCP.

---

## Milestone 5 — Live SSE Streaming

> **Status: ✅ Complete**

**Goal:** Connected SSE clients receive new events in real time via the per-repository
`SubmissionPublisher` fan-out driven by the PostgreSQL `LISTEN` thread.

### What Must Work

| # | Behaviour |
|---|-----------|
| 5.1 | `PublisherRegistry.subscribe(repository, subscriber)` creates a `SubmissionPublisher<ReviewEvent>` for the repository if none exists, then subscribes the client. |
| 5.2 | `PublisherRegistry.publish(event)` submits the event to the publisher keyed by `event.repository()`. If no publisher exists, the call is a no-op — no publisher is created. |
| 5.3 | When the last subscriber for a repository cancels, the publisher is removed from the map to prevent unbounded growth. |
| 5.4 | A slow subscriber whose bounded buffer (capacity 8) fills is dropped via `onError` — the publisher is not back-pressured. |
| 5.5 | `ListenThread` issues `LISTEN events` on a dedicated JDBC connection obtained outside the shared pool. It loops calling `((PGConnection) conn).getNotifications(500)`, deserialises each notification payload as `ReviewEvent` using `GsonFactory`, and calls `PublisherRegistry.publish()`. |
| 5.6 | `ListenThread` runs on a virtual thread. `stop()` interrupts the loop, closes the dedicated connection, and returns. |
| 5.7 | `SseHandler` for `GET /events/stream?repository=<repo>&since=<n>` first replays all stored events with `sequence_no > n` via `queryEvents()`, then subscribes to `PublisherRegistry` and streams live events until the client disconnects. |
| 5.8 | When `since > 0` and `hasEventAt(repository, since)` returns `false`, `SseHandler` responds with `410 Gone` before writing any stream data. |
| 5.9 | When `since = 0`, `SseHandler` always proceeds with replay — `hasEventAt()` is not called. |
| 5.10 | When the `repository` query parameter is absent, `SseHandler` returns `400 Bad Request` with a JSON error body. |
| 5.11 | Each SSE frame has the format: `id: <sequenceNo>\nevent: <eventType>\ndata: <fullEventJson>\n\n`. |
| 5.12 | When a client sends `Last-Event-ID` on reconnect, that value is used as `since` if no explicit `?since=` parameter is present. An explicit `?since=` takes precedence. |
| 5.13 | `SseHandler` sets `Content-Type: text/event-stream`, `Cache-Control: no-cache`, and `X-Accel-Buffering: no` before writing any event data. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `PublisherRegistryTest` | `publisherCreatedOnFirstSubscribe` | Map size increases from 0 to 1 |
| `PublisherRegistryTest` | `publisherRemovedOnLastUnsubscribe` | Map size returns to 0 after last subscriber cancels |
| `PublisherRegistryTest` | `publishToAbsentRepositoryIsNoOp` | No exception; no publisher created |
| `PublisherRegistryTest` | `multipleSubscribersReceiveSameEvent` | Two subscribers both receive the published event |
| `PublisherRegistryTest` | `slowSubscriberDroppedPublisherContinues` | Blocked subscriber cancelled; second fast subscriber still receives all events |
| `SseHandlerTest` | `sseFrameFormatIsCorrect` | Written bytes contain `id: 42\nevent: REVIEW_UPDATED\ndata: {...}\n\n` |
| `SseHandlerTest` | `correctSseResponseHeadersSet` | `Content-Type: text/event-stream` and `Cache-Control: no-cache` present |
| `SseHandlerTest` | `lastEventIdUsedAsSinceWhenParamAbsent` | Replay query issued with `since=3` when `Last-Event-ID: 3` header present |
| `SseHandlerTest` | `sinceParamTakesPrecedenceOverLastEventId` | `?since=5` wins over `Last-Event-ID: 3` |
| `SseHandlerTest` | `missingRepositoryParamReturns400` | HTTP 400 with JSON error body |
| `SseHandlerTest` | `prunedCursorReturns410` | `hasEventAt()` returns `false` → HTTP 410 |
| `SseHandlerTest` | `zeroSinceNeverReturns410` | `since=0` path does not call `hasEventAt()` |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `LiveStreamIT` | `eventSubmittedViaSinkReachesSSEClient` | After `EventSinkImpl.submit()`, connected SSE client receives event within 2 seconds |
| `LiveStreamIT` | `multipleClientsForSameRepoAllReceiveEvent` | Three simultaneous SSE clients all receive a single inserted event |
| `LiveStreamIT` | `clientForDifferentRepoDoesNotReceiveEvent` | Client on `"owner/a"` receives no event when event inserted for `"owner/b"` |
| `LiveStreamIT` | `clientReconnectWithLastEventIdReplaysOnlyMissed` | Client disconnects at `sequenceNo=3`, reconnects with `Last-Event-ID: 3`, receives only events 4+ |
| `LiveStreamIT` | `sseEndpointReturns410WhenCursorPruned` | Insert event with backdated timestamp, prune, reconnect with that `sequenceNo` as `Last-Event-ID` — HTTP 410 returned |

### Gate Criteria

- End-to-end chain confirmed: `EventSinkImpl.submit()` → INSERT + `pg_notify` → `ListenThread` → `PublisherRegistry` → SSE frame received by `HttpURLConnection` client.
- Publisher map size confirmed 0 after all subscribers disconnect.
- `410 Gone` confirmed on `/events/stream` with a real pruned event.

---

## Milestone 6 — Client History API (`GET /events`)

> **Status: 🔲 Not Started**

**Goal:** Clients can retrieve paginated event history and receive `410 Gone` when their
cursor has fallen outside the retention window.

### What Must Work

| # | Behaviour |
|---|-----------|
| 6.1 | `GET /events?repository=<repo>&since=<n>&limit=<l>` returns HTTP 200 with `{"events":[...],"nextSince":<n>}`. `nextSince` is the `sequenceNo` of the last returned event, or the input `since` if the array is empty. |
| 6.2 | `limit` defaults to 100 when absent. |
| 6.3 | An empty `events` array means the client is caught up. |
| 6.4 | When `since > 0` and `hasEventAt(repository, since)` returns `false`, the endpoint returns `410 Gone` with a JSON error body. |
| 6.5 | When `since = 0`, the endpoint always returns events from the oldest retained event; `hasEventAt()` is never called. |
| 6.6 | The `repository` parameter is required. Its absence returns `400 Bad Request` with a JSON error body. |
| 6.7 | All `ReviewEvent` fields (`sequenceNo`, `repository`, `eventType`, `reviewId`, `actorUser`, `timestamp`, `payload`, `deliveryId`) appear in each event JSON; null fields serialise as `null`, not omitted. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `EventsHandlerTest` | `returnsPagedEventsWithCorrectNextSince` | `nextSince` equals last returned event's `sequenceNo` |
| `EventsHandlerTest` | `emptyEventsNextSinceEqualsInputSince` | Empty array; `nextSince` equals input `since` |
| `EventsHandlerTest` | `defaultLimitIs100` | Repository query called with `limit=100` when parameter absent |
| `EventsHandlerTest` | `missingRepositoryReturns400` | HTTP 400 with JSON error body |
| `EventsHandlerTest` | `since0NeverCalls410Check` | `hasEventAt()` never invoked when `since=0` |
| `EventsHandlerTest` | `prunedCursorReturns410` | `hasEventAt()` returns `false` → HTTP 410 |
| `EventsHandlerTest` | `nullFieldsSerializedAsJsonNull` | `"reviewId":null` present in output, not omitted |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `EventsEndpointIT` | `paginatesCorrectly` | Insert 250 events; page 1 returns 100, page 2 returns 100, page 3 returns 50; `nextSince` advances each page |
| `EventsEndpointIT` | `returns410WhenCursorPruned` | Insert backdated event, prune, `GET /events?since=<sequenceNo>` returns 410 |
| `EventsEndpointIT` | `since0ReturnsAllRetainedEvents` | `since=0` returns all 5 inserted events |

### Gate Criteria

- `410 Gone` confirmed with a real pruned event in integration test.
- Pagination cursor `nextSince` correct across three pages.
- Null-field serialisation confirmed.

---

## Milestone 7 — Startup Ordering, Reconciliation & Event Pruning

> **Status: 🔲 Not Started**

**Goal:** The startup sequence executes in the correct order (schema → plugin → reconcile →
LISTEN → HTTP). Reconcile runs with bounded concurrency and per-call timeout. Old events
are pruned on a schedule. Repositories whose history predates the retention window are
skipped during reconciliation.

### What Must Work

| # | Behaviour |
|---|-----------|
| 7.1 | `Main` executes startup in strict order: (1) load config, (2) init database schema, (3) load and validate plugin, (4) call `plugin.start()`, (5) run `ReconciliationRunner.run()`, (6) start `ListenThread`, (7) start `PruneScheduler`, (8) start HTTP server. The HTTP server does **not** accept connections until all preceding steps succeed. |
| 7.2 | `ReconciliationRunner.run()` queries all rows in `repository_state` and calls `plugin.reconcile(repository, lastEventTime)` for each. `since` is `last_event_time` from the row, or `Instant.EPOCH` when null. |
| 7.3 | Reconcile calls are parallelised up to `indexer.reconcileConcurrency` simultaneous calls. |
| 7.4 | Each reconcile call is bounded to `indexer.reconcileTimeoutSeconds`. A call that does not return in time is interrupted with a warning; the slot is released and startup continues for remaining repositories. |
| 7.5 | Repositories present in `config.repositories` but absent from `repository_state` are **not** passed to `reconcile()` — no history exists to backfill. |
| 7.6 | Repositories whose `last_event_time` is older than `now() - retentionDays` are **not** passed to `reconcile()` — any backfilled events would be pruned immediately. |
| 7.7 | `PruneScheduler` calls `EventRepository.pruneOlderThan(retentionDays)` immediately on `start()`, then repeats every `indexer.pruneIntervalHours` hours on a dedicated virtual thread. |
| 7.8 | `PruneScheduler.shutdown()` stops the periodic task cleanly. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `ReconciliationRunnerTest` | `callsReconcileForEachRepoStateRow` | `plugin.reconcile()` called once per repository in `repository_state` |
| `ReconciliationRunnerTest` | `concurrencyBoundRespected` | With `concurrency=2` and 4 repos, peak simultaneous calls ≤ 2 (tracked via `AtomicInteger`) |
| `ReconciliationRunnerTest` | `timeoutInterruptsSlowReconcile` | Plugin sleeping 60 s interrupted after configured timeout; remaining repos still reconciled |
| `ReconciliationRunnerTest` | `reposAbsentFromStateSkipped` | Repo in `config.repositories` but not in `repository_state` not passed to `reconcile()` |
| `ReconciliationRunnerTest` | `reposOlderThanRetentionWindowSkipped` | Repo whose `last_event_time` predates retention cutoff not passed to `reconcile()` |
| `PruneSchedulerTest` | `prunesImmediatelyOnStart` | `pruneOlderThan()` called within 200 ms of `start()` |
| `PruneSchedulerTest` | `shutdownDoesNotThrow` | `shutdown()` returns cleanly |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `ReconciliationRunnerIT` | `backfilledEventsStoredAfterReconcile` | Plugin calls `EventSink.submit()` during `reconcile()`; event row exists in `events` table |
| `ReconciliationRunnerIT` | `duplicateFromReconcileNotDoubleInserted` | Same `deliveryId` submitted twice during reconcile; only one row in `events` |
| `StartupOrderingIT` | `httpServerNotOpenBeforeReconcileCompletes` | Slow `reconcile()` (sleep 2 s) delays HTTP server; `GET /health` throws `ConnectException` during reconcile, then `200 OK` after |
| `PruneSchedulerIT` | `pruneRunsAndRemovesExpiredRows` | Events beyond retention window removed after scheduler starts |

### Gate Criteria

- `timeoutInterruptsSlowReconcile` passes: slow plugin interrupted; remaining repos processed.
- `StartupOrderingIT` confirms HTTP server unavailable during reconcile and available after.
- Retention-window skip and deduplication confirmed end-to-end.

---

## Milestone 8 — Webhook Retry Queue

> **Status: 🔲 Not Started**

**Goal:** When PostgreSQL is temporarily unavailable during webhook delivery, events are
held in a bounded in-memory queue and retried with exponential back-off, absorbing
transient outages so providers do not start their 24-hour retry clock unnecessarily.

### What Must Work

| # | Behaviour |
|---|-----------|
| 8.1 | `RetryQueue.offer(event)` adds the event to the queue if depth < `retryQueue.maxDepth`; returns `false` immediately when at capacity. |
| 8.2 | A background virtual thread drains the queue, retrying each event's persistence with delays of 1 s, 2 s, 4 s, 8 s, 16 s, 30 s (capped). |
| 8.3 | An event that cannot be persisted within `retryQueue.maxRetryMinutes` is discarded with a `WARN` log including the `deliveryId` and repository. |
| 8.4 | When `EventSinkImpl.submit()` catches a `SQLException`, it calls `RetryQueue.offer()`. If `offer()` returns `false`, `submit()` throws `RetryQueueFullException` — caught by the webhook dispatcher to return `503`. |
| 8.5 | When `offer()` returns `true`, the webhook dispatcher returns `202 Accepted`. |
| 8.6 | On successful persistence from the retry thread, `PublisherRegistry.publish()` is called — live SSE clients receive the event as soon as the DB recovers. |
| 8.7 | `RetryQueue.shutdown()` stops accepting new events and waits up to `maxRetryMinutes` for in-flight retries before returning. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `RetryQueueTest` | `offerReturnsFalseAtCapacity` | After `maxDepth` offers, next `offer()` returns `false` without blocking |
| `RetryQueueTest` | `eventRetriedUntilInsertSucceeds` | Mock insert throws `SQLException` twice then succeeds; `publish()` called once after third attempt |
| `RetryQueueTest` | `eventDiscardedAfterMaxRetryDuration` | Insert always fails; WARN log present; event absent from queue after timeout |
| `RetryQueueTest` | `backOffDelaysAreExponential` | Retry intervals ≥ 1 s, ≥ 2 s, ≥ 4 s (measured with injected clock) |
| `RetryQueueTest` | `backOffCapsAt30Seconds` | Delay never exceeds 30 s after many consecutive failures |
| `EventSinkImplTest` | `sqlExceptionTriggersRetryQueueOffer` | `insert()` throws `SQLException`; `RetryQueue.offer()` called once |
| `EventSinkImplTest` | `fullQueueThrowsRetryQueueFullException` | `offer()` returns `false`; `submit()` throws `RetryQueueFullException` |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `RetryQueueIT` | `eventPersistedAfterDatabaseRecovers` | Stop PostgreSQL container; call `EventSinkImpl.submit()` — event queued. Restart container. Within `maxRetryMinutes`, event appears in `events` table and connected SSE client receives it. |

### Gate Criteria

- `RetryQueueIT` executes a full container stop/start cycle without the caller seeing a `503`.
- `503` path confirmed: queue-full causes webhook dispatcher to return 503.
- Back-off cap at 30 s confirmed without `>30 s` wall-clock wait (injected clock).

---

## Milestone 9 — TLS Support

> **Status: 🔲 Not Started**

**Goal:** The HTTP server can optionally terminate TLS directly using a configured keystore,
enabling operators who expose the indexer on a public interface without a reverse proxy.

### What Must Work

| # | Behaviour |
|---|-----------|
| 9.1 | When `server.tls.enabled` is `false` (or the `tls` block is absent), the server starts as plain `HttpServer` — identical to all previous milestones. |
| 9.2 | When `server.tls.enabled` is `true`, an `HttpsServer` is bound using a `KeyManagerFactory` loaded from `keystorePath`, `keystoreType`, and `keystorePassword`. |
| 9.3 | A missing or unreadable keystore when TLS is enabled causes startup to fail immediately with a `TlsConfigurationException` whose message includes the configured path. |
| 9.4 | An incorrect `keystorePassword` causes immediate startup failure with a `TlsConfigurationException`. |
| 9.5 | All endpoints function identically over HTTPS. |

### Unit Tests

| Test class | Test name | Assertion |
|---|---|---|
| `TlsConfiguratorTest` | `plainHttpWhenTlsDisabled` | Returns `HttpServer` (not `HttpsServer`) |
| `TlsConfiguratorTest` | `plainHttpWhenTlsBlockAbsent` | Null `tls` config treated as `enabled=false` |
| `TlsConfiguratorTest` | `httpsServerWhenTlsEnabled` | Returns `HttpsServer` with correctly loaded `SSLContext` |
| `TlsConfiguratorTest` | `throwsOnMissingKeystoreFile` | `TlsConfigurationException` with path in message |
| `TlsConfiguratorTest` | `throwsOnWrongKeystorePassword` | `TlsConfigurationException` with meaningful message |

### Integration Tests

| Test class | Test name | Assertion |
|---|---|---|
| `TlsIT` | `healthEndpointReachableOverHttps` | `GET https://localhost:<port>/health` via `HttpsURLConnection` with test trust store returns 200 |
| `TlsIT` | `sseStreamReachableOverHttps` | SSE stream over HTTPS receives replayed events |

### Gate Criteria

- Self-signed test certificate generated programmatically — no checked-in cert files.
- All previous milestone integration tests pass unchanged with TLS disabled.

---

## Milestone 10 — Deployment Packaging & System Test

> **Status: 🔲 Not Started**

**Goal:** The application is packaged as a Docker image with a `docker-compose.yml` that
brings up the indexer and PostgreSQL together. A full end-to-end system test confirms the
complete event flow from simulated webhook through SSE delivery to a real HTTP client.

### What Must Work

| # | Behaviour |
|---|-----------|
| 10.1 | `pom.xml` builds a fat JAR with `maven-assembly-plugin` — all runtime dependencies bundled; plugin JARs excluded (drop-in at `./plugins`). |
| 10.2 | `Dockerfile` uses a JDK 21+ base image, copies the fat JAR, exposes port 8765, and sets `ENTRYPOINT ["java", "-jar", "central-indexer.jar"]`. |
| 10.3 | `docker-compose.yml` defines the `indexer` service (bind-mounting `./plugins` and `./config.json`) and the `postgres:16` companion with `restart: unless-stopped` on both services. |
| 10.4 | `docker compose up` from a clean checkout with a valid `config.json` and real plugin JAR starts both services, creates the schema, and serves `/health` returning `{"status":"UP","db":"UP"}` within 30 seconds. |
| 10.5 | `Main` registers a JVM shutdown hook that executes in this order: (1) stop accepting new HTTP requests, (2) `PluginLoader.close()` — `plugin.stop()` then `URLClassLoader.close()`, (3) `PruneScheduler.shutdown()`, (4) `RetryQueue.shutdown()`, (5) `ListenThread.stop()`, (6) `ConnectionPool.close()`. Each step completes before the next begins. |

### System Tests (Integration)

| Test class | Test name | Assertion |
|---|---|---|
| `SystemIT` | `fullFlowWebhookToSse` | (1) SSE client opens `GET /events/stream?repository=owner/test`. (2) `POST /webhooks/<suffix>` sent with plugin-expected payload. (3) SSE client receives the event within 3 seconds; `id:`, `event:`, and `data:` fields all correct. |
| `SystemIT` | `clientReconnectAfterServerRestartMissesNoEvents` | Client receives events 1–3. Server stopped. Events 4–5 queued via retry queue. Server restarted. Client reconnects with `Last-Event-ID: 3` and receives events 4 and 5 with no gaps. |
| `SystemIT` | `healthCheckDrivesStartupReadiness` | Poll `/health` every 500 ms; `db` field transitions from `"DOWN"` to `"UP"` within 30 s of `docker compose up`. |

### Gate Criteria

- `docker compose up` works from a clean checkout.
- `fullFlowWebhookToSse` passes against the live compose stack.
- Shutdown hook order confirmed: `plugin.stop()` then `URLClassLoader.close()` verified; no orphaned threads.
- `clientReconnectAfterServerRestartMissesNoEvents` passes: no events lost across a restart with retry queue active.

---

## Summary Table

| Milestone | Focus | Unit Tests | Integration Tests | Key Gate |
|---|---|---|---|---|
| M0 | Test Infrastructure | 2 | 2 | `PostgresTestContainer` works; `@RequiresDocker` skips gracefully |
| M1 | Config & DB Bootstrap | 10 | 4 | Fail-fast on DB unreachable; all 3 indexes created |
| M2 | Event Persistence | 10 | 5 | Concurrent inserts unique; `pg_notify` payload round-trips |
| M3 | Plugin System | 10 | 2 | Real JAR loaded; `URLClassLoader` always closed |
| M4 | HTTP & Auth | 10 | 6 | All auth scenarios (incl. disabled) confirmed live |
| M5 | Live SSE Streaming | 12 | 5 | Full end-to-end chain; 410 on stream; map cleanup |
| M6 | Client History API | 7 | 3 | 410 confirmed with real pruned event; pagination correct |
| M7 | Reconciliation & Pruning | 7 | 4 | HTTP blocked during reconcile; retention skip; timeout |
| M8 | Retry Queue | 7 | 1 | Live container stop/start; 503 on full queue |
| M9 | TLS Support | 5 | 2 | HTTPS health and SSE; no regression |
| M10 | Deployment & System Test | — | 3 | Full compose flow; reconnect misses no events |
| **Total** | | **80** | **37** | |


