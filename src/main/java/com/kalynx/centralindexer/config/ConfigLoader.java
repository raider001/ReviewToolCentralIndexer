package com.kalynx.centralindexer.config;

import com.google.gson.JsonSyntaxException;
import com.kalynx.centralindexer.exception.ConfigLoadException;
import com.kalynx.centralindexer.json.GsonFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and deserialises the application configuration from {@code config.json}.
 *
 * <h2>Config file location</h2>
 * Resolved in priority order — the first match wins:
 * <ol>
 *   <li>System property {@code cri.config} (absolute or relative path).</li>
 *   <li>Environment variable {@code CRI_CONFIG}.</li>
 *   <li>{@code ./config.json} relative to the working directory.</li>
 * </ol>
 *
 * <h2>Environment variable substitution</h2>
 * All occurrences of {@code ${ENV_VAR}} in the raw JSON are replaced with the
 * corresponding environment variable value before Gson deserialisation. A missing
 * variable causes an {@link IllegalStateException}.
 */
public final class ConfigLoader {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private ConfigLoader() {
    }

    /**
     * Loads the application configuration using live environment variables.
     *
     * @return a fully deserialised {@link AppConfig}
     * @throws ConfigLoadException  if the file cannot be found, read, or parsed
     * @throws IllegalStateException if a {@code ${ENV_VAR}} placeholder references a
     *                               missing environment variable
     */
    public static AppConfig load() {
        return load(System::getenv);
    }

    /**
     * Loads the application configuration using the supplied environment resolver.
     *
     * <p>This overload exists to support unit testing without needing to set real
     * environment variables.
     *
     * @param envProvider a function that returns the value of a named environment variable,
     *                    or {@code null} when the variable is not set
     * @return a fully deserialised {@link AppConfig}
     * @throws ConfigLoadException  if the file cannot be found, read, or parsed
     * @throws IllegalStateException if a {@code ${ENV_VAR}} placeholder references a
     *                               missing environment variable
     */
    static AppConfig load(Function<String, String> envProvider) {
        Path configPath = resolveConfigPath(envProvider);
        String rawJson = readFile(configPath);
        String substituted = substituteEnvVars(rawJson, envProvider);
        return deserialise(substituted, configPath);
    }

    private static Path resolveConfigPath(Function<String, String> envProvider) {
        String fromProperty = System.getProperty("cri.config");
        if (fromProperty != null) {
            return Paths.get(fromProperty);
        }
        String fromEnv = envProvider.apply("CRI_CONFIG");
        if (fromEnv != null) {
            return Paths.get(fromEnv);
        }
        return Paths.get("./config.json");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new ConfigLoadException("Cannot read config file: " + path.toAbsolutePath(), e);
        }
    }

    private static String substituteEnvVars(String json, Function<String, String> envProvider) {
        Matcher matcher = ENV_PATTERN.matcher(json);
        StringBuilder result = new StringBuilder(json.length());
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = envProvider.apply(varName);
            if (value == null) {
                throw new IllegalStateException(
                        "Config references environment variable '${" + varName + "}' which is not set");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static AppConfig deserialise(String json, Path configPath) {
        try {
            AppConfig config = GsonFactory.getInstance().fromJson(json, AppConfig.class);
            if (config == null) {
                throw new ConfigLoadException("Config file is empty: " + configPath.toAbsolutePath());
            }
            return config;
        } catch (JsonSyntaxException e) {
            throw new ConfigLoadException(
                    "Config file contains invalid JSON: " + configPath.toAbsolutePath(), e);
        }
    }
}


