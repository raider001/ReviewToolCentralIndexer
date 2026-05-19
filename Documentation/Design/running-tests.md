# Running the Tests

## Unit tests

```bash
mvn test -pl ReviewToolCentralIndexer
```

No external dependencies are required. Unit tests run in isolation using Mockito mocks and complete in seconds.

## Integration tests

The integration tests (classes ending in `IT`) spin up a real **PostgreSQL 16** container via the Docker CLI. The only prerequisite is a working Docker installation.

**Prerequisites:**
- Docker Desktop (Windows/macOS) or Docker Engine (Linux) installed and running.
- The `docker` command must be on `PATH`.

**Running:**

```bash
mvn test -pl ReviewToolCentralIndexer
```

Integration tests are included in the same `mvn test` phase as unit tests — no separate phase or profile is needed. Each `*IT` class is annotated with `@RequiresDocker`. If `docker info` exits non-zero (Docker not installed or the daemon is not running), the entire test class is **skipped automatically** with a human-readable reason rather than failing with an error.

**What the tests do:**

| Package | Test class | What it covers |
|---|---|---|
| `it.support` | `PostgresTestContainerIT` | Verifies that `PostgresTestContainer` itself can start a container, accept a JDBC connection, and stop cleanly. |
| `it.db` | `ConnectionPoolIT` | Verifies acquire/release against a real database and that `ConnectionPool` fails fast when the database is unreachable. |
| `it.db` | `DatabaseInitializerIT` | Verifies that `DatabaseInitializer.init()` creates the `events` and `repository_state` tables and their indexes, and that running it twice is idempotent. |

**Package layout:**

```
src/test/java/com/kalynx/centralindexer/
  config/                         ← unit tests
  db/                             ← unit tests
  json/                           ← unit tests
  support/                        ← unit tests
  it/
    support/                      ← Docker infrastructure + its own IT
      PostgresTestContainer.java
      RequiresDocker.java
      RequiresDockerCondition.java
      RequiresDockerConditionTest.java
      PostgresTestContainerIT.java
    db/                           ← integration tests for db layer
      ConnectionPoolIT.java
      DatabaseInitializerIT.java
```

**How `PostgresTestContainer` works:**

The helper (`it.support.PostgresTestContainer`) uses the Docker CLI directly — no Testcontainers library is involved. On construction it runs `docker run -d --rm -p 0:5432 postgres:16`, discovers the randomly assigned host port via `docker port`, and polls for a JDBC connection (up to 30 seconds). On `close()` it stops the container, which Docker removes automatically due to the `--rm` flag. Tests use it in a try-with-resources block so the container is always cleaned up even if the test fails.

