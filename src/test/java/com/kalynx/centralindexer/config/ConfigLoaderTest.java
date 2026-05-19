package com.kalynx.centralindexer.config;

import com.kalynx.centralindexer.exception.ConfigLoadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigLoader}.
 */
class ConfigLoaderTest {

    private static final String MINIMAL_JSON = """
            {
              "database": {
                "url": "jdbc:postgresql://localhost:5432/indexer",
                "user": "indexer",
                "password": "secret"
              },
              "plugin": {
                "providerId": "github",
                "properties": {}
              }
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void loadsFromSystemPropertyPath() throws IOException {
        Path configFile = writeConfig("system-prop.json", MINIMAL_JSON);
        String previous = System.getProperty("cri.config");
        try {
            System.setProperty("cri.config", configFile.toString());
            AppConfig config = ConfigLoader.load(System::getenv);
            assertEquals("jdbc:postgresql://localhost:5432/indexer", config.getDatabase().getUrl());
        } finally {
            restoreProperty("cri.config", previous);
        }
    }

    @Test
    void systemPropertyTakesPriorityOverEnvVar() throws IOException {
        Path systemPropertyFile = writeConfig("system-prop.json", MINIMAL_JSON);
        Path envVarFile = writeConfig("env-var.json", MINIMAL_JSON.replace("5432", "5433"));
        String previous = System.getProperty("cri.config");
        try {
            System.setProperty("cri.config", systemPropertyFile.toString());
            Function<String, String> env = name -> "CRI_CONFIG".equals(name) ? envVarFile.toString() : null;
            AppConfig config = ConfigLoader.load(env);
            assertTrue(config.getDatabase().getUrl().contains("5432"),
                    "System property path should win; expected port 5432 but got: " + config.getDatabase().getUrl());
        } finally {
            restoreProperty("cri.config", previous);
        }
    }

    @Test
    void envVarTakesPriorityOverDefaultPath() throws IOException {
        Path envVarFile = writeConfig("env-var.json", MINIMAL_JSON);
        String previousProperty = System.getProperty("cri.config");
        System.clearProperty("cri.config");
        try {
            Function<String, String> env = name -> "CRI_CONFIG".equals(name) ? envVarFile.toString() : null;
            AppConfig config = ConfigLoader.load(env);
            assertEquals("jdbc:postgresql://localhost:5432/indexer", config.getDatabase().getUrl());
        } finally {
            restoreProperty("cri.config", previousProperty);
        }
    }

    @Test
    void substitutesEnvVars() throws IOException {
        String json = MINIMAL_JSON.replace("\"secret\"", "\"${DB_PASS}\"");
        Path configFile = writeConfig("substitute.json", json);
        String previous = System.getProperty("cri.config");
        try {
            System.setProperty("cri.config", configFile.toString());
            Function<String, String> env = name -> "DB_PASS".equals(name) ? "runtime-password" : null;
            AppConfig config = ConfigLoader.load(env);
            assertEquals("runtime-password", config.getDatabase().getPassword());
        } finally {
            restoreProperty("cri.config", previous);
        }
    }

    @Test
    void throwsOnMissingEnvVar() throws IOException {
        String json = MINIMAL_JSON.replace("\"secret\"", "\"${MISSING_VAR}\"");
        Path configFile = writeConfig("missing-var.json", json);
        String previous = System.getProperty("cri.config");
        try {
            System.setProperty("cri.config", configFile.toString());
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> ConfigLoader.load(name -> null));
            assertTrue(ex.getMessage().contains("MISSING_VAR"),
                    "Exception message should name the missing variable");
        } finally {
            restoreProperty("cri.config", previous);
        }
    }

    @Test
    void throwsOnInvalidJson() throws IOException {
        Path configFile = writeConfig("invalid.json", "{ this is not valid json }");
        String previous = System.getProperty("cri.config");
        try {
            System.setProperty("cri.config", configFile.toString());
            ConfigLoadException ex = assertThrows(
                    ConfigLoadException.class,
                    () -> ConfigLoader.load(name -> null));
            assertTrue(ex.getMessage().contains("invalid JSON") || ex.getCause() != null,
                    "Exception should describe JSON parse failure");
        } finally {
            restoreProperty("cri.config", previous);
        }
    }

    private Path writeConfig(String filename, String content) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}







