package com.kalynx.centralindexer.it.support;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Starts and manages a real PostgreSQL 16 Docker container for integration tests.
 *
 * <p>On construction the container is started via the Docker CLI, the randomly
 * assigned host port is discovered, and the class blocks until PostgreSQL accepts
 * JDBC connections (polled every 250 ms, up to 30 seconds). On {@link #close()}
 * the container is stopped and auto-removed (via the {@code --rm} flag).
 *
 * <p>Intended to be used in a try-with-resources block in JUnit 5 integration tests
 * annotated with {@link RequiresDocker}.
 */
public final class PostgresTestContainer implements AutoCloseable {

    private static final String DB_NAME = "indexer";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "test";
    private static final long READY_POLL_MS = 250;
    private static final long READY_TIMEOUT_MS = 30_000;

    private final String containerId;
    private final String jdbcUrl;

    /**
     * Starts a fresh PostgreSQL 16 container and blocks until it is ready.
     *
     * @throws ContainerStartException if the container could not be started or did not
     *                                  become ready within 30 seconds
     */
    public PostgresTestContainer() {
        containerId = startContainer();
        int port = discoverPort(containerId);
        jdbcUrl = "jdbc:postgresql://localhost:" + port + "/" + DB_NAME;
        waitUntilReady();
    }

    /**
     * Returns the JDBC URL for the running container.
     *
     * @return a JDBC URL in the form {@code jdbc:postgresql://localhost:<port>/indexer}
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * Returns the database username.
     *
     * @return {@code "postgres"}
     */
    public String getUser() {
        return DB_USER;
    }

    /**
     * Returns the database password.
     *
     * @return {@code "test"}
     */
    public String getPassword() {
        return DB_PASSWORD;
    }

    /**
     * Stops the container. The {@code --rm} flag causes Docker to remove it automatically.
     */
    @Override
    public void close() {
        try {
            new ProcessBuilder("docker", "stop", containerId)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ContainerStartException("Failed to stop container " + containerId, e);
        }
    }

    private String startContainer() {
        try {
            Process process = new ProcessBuilder(
                    "docker", "run", "-d", "--rm",
                    "-p", "0:5432",
                    "-e", "POSTGRES_PASSWORD=" + DB_PASSWORD,
                    "-e", "POSTGRES_DB=" + DB_NAME,
                    "postgres:16"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();

            process.waitFor(30, TimeUnit.SECONDS);
            String id = new BufferedReader(new InputStreamReader(process.getInputStream()))
                    .readLine();
            if (id == null || id.isBlank()) {
                throw new ContainerStartException("docker run produced no container ID");
            }
            return id.trim();
        } catch (ContainerStartException e) {
            throw e;
        } catch (Exception e) {
            throw new ContainerStartException("Failed to start PostgreSQL container", e);
        }
    }

    private int discoverPort(String id) {
        try {
            Process process = new ProcessBuilder("docker", "port", id, "5432")
                    .redirectErrorStream(true)
                    .start();
            process.waitFor(10, TimeUnit.SECONDS);
            String line = new BufferedReader(new InputStreamReader(process.getInputStream()))
                    .readLine();
            if (line == null || line.isBlank()) {
                throw new ContainerStartException("docker port returned no output for container " + id);
            }
            String[] parts = line.split(":");
            return Integer.parseInt(parts[parts.length - 1].trim());
        } catch (ContainerStartException e) {
            throw e;
        } catch (Exception e) {
            throw new ContainerStartException("Failed to discover port for container " + id, e);
        }
    }

    private void waitUntilReady() {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD)) {
                conn.createStatement().execute("SELECT 1");
                return;
            } catch (SQLException ignored) {
            }
            try {
                Thread.sleep(READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerStartException("Interrupted while waiting for PostgreSQL to become ready");
            }
        }
        throw new ContainerStartException("PostgreSQL container did not become ready within 30 seconds");
    }

    /**
     * Thrown when the Docker container cannot be started or does not become ready in time.
     */
    public static final class ContainerStartException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructs a {@code ContainerStartException} with a detail message.
         *
         * @param message description of the failure
         */
        public ContainerStartException(String message) {
            super(message);
        }

        /**
         * Constructs a {@code ContainerStartException} with a detail message and cause.
         *
         * @param message description of the failure
         * @param cause   the underlying exception
         */
        public ContainerStartException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}


