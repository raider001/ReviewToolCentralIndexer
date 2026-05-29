# Cleanup — Roadmap (Technical Spike 4)

## Status Summary

- ✅ **C0** — Delete dead code (backfill package, verify no live usage)
- ✅ **C1** — Naming conventions + SQL constants
- ✅ **C2** — `MetricsCollector` decomposition + `Lifecycle` interface
- ✅ **C3** — Startup wiring: Dependency Injection + `Application` cleanup
- ✅ **C4** — Reconciler hierarchy: common interface + decomposition
- ✅ **C5** — GitHub provider cleanup
- ✅ **C6** — Unit test naming conventions

---

## Purpose

This spike addresses accumulated technical debt across `ReviewToolCentralIndexer`. No new features are introduced. Each milestone leaves the project compiling and all tests passing before the next begins.

---

## C0 — Delete dead code

### Backfill package

The `backfill` package (`BackfillBranchesTool`, `BackfillMain`, `BackfillOptions`, `BackfillReport`) was introduced for a one-shot data migration. Before deleting:

1. Confirm no startup or runtime path calls into it.
2. Decouple the backfill progress metric from `MetricsCollector` / `MetricsHandler` / `GitHubBranchReconciler` — remove or replace with a no-op counter.
3. Delete all four source files, `BackfillBranchesIT`, and any `backfill`-keyed metric references.

### GUI package in `ReviewToolCentralIndexer`

Already removed in Spike 3. The standalone `ReviewToolCentralIndexerGui` is the only GUI.

---

## C1 — Naming conventions + SQL constants

### Logger naming

Rename `log` → `LOGGER` (static constant convention) in:
- `Application`
- `StartupReconciler`
- `RepositoriesFileLoader`
- `GitHubBranchReconciler`

### Shared DB schema constants

Introduce `com.kalynx.centralindexer.db.DbSchema` with `public static final String` constants for every table name, column name, constraint name, and index name referenced in more than one place.

Example shape:
```java
public final class DbSchema {
    // Tables
    public static final String TABLE_REPOSITORIES   = "repositories";
    public static final String TABLE_BRANCHES       = "branches";
    public static final String TABLE_REVIEW_BRANCHES = "review_branches";
    public static final String TABLE_REVIEWS_INDEX  = "reviews_index";
    public static final String TABLE_COMMENTS_INDEX = "comments_index";

    // Columns — repositories
    public static final String COL_REPOSITORY_ID   = "repository_id";
    public static final String COL_OWNER           = "owner";
    public static final String COL_REPOSITORY      = "repository";
    public static final String COL_URL             = "url";
    // ... etc.
}
```

### Per-repository SQL constants

In each of `RepositoriesRepository`, `BranchRepository`, `ReviewsIndexRepository`, `CommentsIndexRepository`:

- Move every SQL string into a `private static final String SQL_*` constant at the top of the class.
- Build constants from `DbSchema` where possible (table and column names), keeping the SQL logic in the constant value itself.
- No inline string concatenation in method bodies.

---

## C2 — `MetricsCollector` decomposition + `Lifecycle` interface

### `Lifecycle` interface

```java
public interface Lifecycle {
    void start();
    void stop();
}
```

Apply to: `MetricsCollector`, `Application`, and any reconciler or plugin wrapper that has a matching start/stop pair.

### Remove singleton

Delete `MetricsCollector.initialize(pool)` and any static `getInstance()`. Construct one instance in `Main` and pass it through the object graph.

### Guard double-start

`start()` must be idempotent. Use `AtomicBoolean started` to prevent duplicate background threads.

### Single time source

Use `System.nanoTime()` consistently throughout the collector. Remove all `System.currentTimeMillis()` calls inside metrics classes.

### Decompose into sub-collectors

Extract each responsibility into its own class, each with a `record()` method (to accumulate a sample) and a `snapshot()` method (to read the current value):

| Class | Responsibility |
|---|---|
| `MemoryMetrics` | JVM heap used / max |
| `CpuMetrics` | overall + per-core CPU percent |
| `ConnectionMetrics` | active DB connections, pool waiting threads |
| `SseEventMetrics` | connected clients, write latency, event counts broken down by event type |

`MetricsCollector` becomes a coordinator: it holds one instance of each, owns the background sampling thread, and exposes a combined snapshot.

### Variable order

Move all instance variables to the top of the class, above constructors, following the conventions used elsewhere in the project.

---

## C3 — Startup wiring: DI + `Application` cleanup

### `Main.java` — adopt `DependencyInjector`

Use `com.kalynx.lwdi.DependencyInjector` (same framework as `ReviewToolApplication`). Register:
- `ConnectionPool`
- `MetricsCollector`
- `PublisherRegistry`
- `WebhookRouterImpl`
- `ProviderPlugin` (resolved via `PluginLoader` or direct construction — see PluginLoader note below)
- Repository classes (`RepositoriesRepository`, `BranchRepository`, `ReviewsIndexRepository`, `CommentsIndexRepository`)

Let `Application` be injected rather than manually constructed.

### `Application.start()`

Move repository instantiation out of `start()` — they should arrive as injected constructor parameters, not be newed up inside the method. `start()` should only coordinate the startup sequence.

Implement `Lifecycle` on `Application`.

### Extract anonymous classes

The two lambdas passed to `EventSinkImpl` (`setNewRepositoryCallback`, `setKalynxReviewsUpdateCallback`) should become named package-private classes with clear names, instantiated in `Application`.

### `EventSinkImpl` usage

At all call sites, depend on the `EventSink` interface rather than the concrete `EventSinkImpl`.

### `PluginLoader` design decision

Currently `PluginLoader` loads a JAR dynamically, but the GitHub provider is built in directly. This is confusing. Two options:

- **Option A (simple):** Remove `PluginLoader` entirely; register `GitHubPlugin` (or a `NoopPlugin`) directly in `Main`.
- **Option B (extensible):** Keep `PluginLoader` and wire the GitHub provider as the default plugin JAR, making the dynamic-loading path actually work end-to-end.

Decide before implementing C3.

---

## C4 — Reconciler hierarchy

### Common interface

```java
public interface Reconciler {
    void reconcile();
}
```

### Decompose `StartupReconciler`

Break into focused reconcilers, each implementing `Reconciler`:

| Class | Responsibility |
|---|---|
| `RepositoryReconciler` | Discovers and seeds repository records |
| `BranchReconciler` | Reconciles branch heads for a given repository |
| `ReviewReconciler` | Reconciles the `kalynx-reviews` ref for a given repository |
| `CommentReconciler` | Already exists; bring into this hierarchy |

### `StartupReconciler` becomes a coordinator

Holds a list of `Reconciler` instances; iterates and calls `reconcile()` on each. Reduces to ~20 lines.

### `reconcileKalynxReviews` URL construction

Determine whether the URL path being built is already available from an existing field or helper. If so, delegate and remove the duplication.

---

## C5 — GitHub provider cleanup

### `GitHubBranchReconciler`

- Move the hardcoded `kalynx-reviews` branch name to a config property (e.g. `plugin.reviewsBranchName`), defaulting to the current value.
- Replace all `token == null || token.isBlank()` checks with a single `StringUtils.isBlank(token)` helper or a validated `BearerToken` value object.
- Identify strings shared with `GitHubPlugin` or `GitHubReconciler`; hoist to `GitHubConstants`.

### `GitHubPlugin` + `GitHubReconciler`

- Extract every string literal that appears more than once into a `private static final` constant in the owning class.
- Any constant shared between the two classes moves to `GitHubConstants`.
- Distinguish clearly: GitHub-specific constants stay in `GitHubConstants`; generic HTTP or review constants belong in a shared location.

---

## C6 — Unit test naming conventions

All test method names must follow the pattern:

```
{methodName}_{scenario}_{expectedBehaviour}
```

Examples:
- `extractReviewId_nullPath_returnsNull`
- `handle_nonGetRequest_returns405`
- `upsert_duplicateCommentId_advancesLastUpdated`
- `detect_metadataPath_classifiedAsAdded`

### Scope

Rename all existing test methods across every test class in `src/test/` that do not already follow this convention. This includes unit tests, handler tests, and integration tests.

### Rules

- `methodName` — the name of the method or behaviour under test (use the class name for constructor/lifecycle tests, e.g. `init_freshDatabase_createsAllTables`).
- `scenario` — the input condition or state being exercised.
- `expectedBehaviour` — the observable outcome (return value, exception, side-effect, status code, etc.).
- Use camelCase within each segment; separate segments with underscores.
- Keep names descriptive but concise — avoid restating the class name if it is already in the test class name.

---

## Completion Criteria

Each milestone is complete when:
1. `mvn compile` passes with no errors.
2. `mvn test -Dgroups=""` (unit tests) passes.
3. The milestone items listed above are fully addressed — no partial implementations.

**Before starting the next milestone, update the status symbol for the completed milestone in the Status Summary at the top of this document from ⬜ to ✅. Do not begin C(n+1) until C(n) is marked complete.**
