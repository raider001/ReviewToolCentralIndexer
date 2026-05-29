package com.kalynx.centralindexer.it.support;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PostgresTestContainer}.
 *
 * <p>These tests require Docker to be available and will be skipped automatically
 * when it is not.
 */
@RequiresDocker
class PostgresTestContainerIT {

    @Test
    void containerStarts_dockerAvailable_acceptsConnections() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            Connection conn = DriverManager.getConnection(
                    container.getJdbcUrl(), container.getUser(), container.getPassword());
            ResultSet rs = conn.createStatement().executeQuery("SELECT 1");
            rs.next();
            assertEquals(1, rs.getInt(1));
            conn.close();
        }
    }

    @Test
    void close_runningContainer_stopsContainer() throws Exception {
        String containerId;
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            containerId = extractContainerId(container);
            assertTrue(isContainerRunning(containerId), "Container should be running before close()");
        }
        assertFalse(isContainerRunning(containerId), "Container should be stopped after close()");
    }

    private String extractContainerId(PostgresTestContainer container) throws Exception {
        Process process = new ProcessBuilder("docker", "ps", "-q", "--no-trunc")
                .redirectErrorStream(true)
                .start();
        process.waitFor(10, TimeUnit.SECONDS);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                return line.trim();
            }
        }
        throw new IllegalStateException("No running containers found");
    }

    private boolean isContainerRunning(String id) throws Exception {
        Process process = new ProcessBuilder("docker", "ps", "-q", "--no-trunc")
                .redirectErrorStream(true)
                .start();
        process.waitFor(10, TimeUnit.SECONDS);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith(id) || id.startsWith(line.trim())) {
                    return true;
                }
            }
        }
        return false;
    }
}

