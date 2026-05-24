package com.kalynx.centralindexer.config;

import com.kalynx.centralindexer.json.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads tracked repository definitions from {@code repositories.json}.
 *
 * <h2>File location</h2>
 * Resolved in priority order — the first match wins:
 * <ol>
 *   <li>System property {@code cri.repositories} (absolute or relative path).</li>
 *   <li>Environment variable {@code CRI_REPOSITORIES}.</li>
 *   <li>{@code ./repositories.json} relative to the working directory.</li>
 * </ol>
 *
 * <p>If the resolved file does not exist the loader returns an empty list and logs a
 * warning — repositories will be populated later when branch-push webhooks arrive.
 *
 * <h2>File format</h2>
 * A JSON array of objects. Each object requires a {@code url} field; an optional
 * {@code name} label is accepted for human readability but is not used at runtime.
 * {@code owner} and {@code repository} are derived from the URL path — strips any
 * trailing {@code .git} suffix first:
 * <pre>{@code
 * [
 *   { "url": "https://github.com/my-org/my-repo" },
 *   { "url": "https://github.com/my-org/another-repo.git", "name": "Another Repo" }
 * ]
 * }</pre>
 */
public final class RepositoriesFileLoader {

    private static final Logger log = LoggerFactory.getLogger(RepositoriesFileLoader.class);

    private RepositoriesFileLoader() {
    }

    /** Loads repositories using live system properties and environment variables. */
    public static List<RepositoryConfig> load() {
        return load(System::getenv);
    }

    /** Returns the resolved path to the repositories file using live system properties and env vars. */
    public static Path resolvePath() {
        return resolvePath(System::getenv);
    }

    /**
     * Loads repositories using the supplied environment resolver (for testing).
     *
     * @param envProvider maps an environment variable name to its value, or {@code null}
     * @return an immutable list of {@link RepositoryConfig} entries parsed from the file,
     *         or an empty list if the file does not exist or has no valid entries
     */
    public static List<RepositoryConfig> load(java.util.function.Function<String, String> envProvider) {
        Path path = resolvePath(envProvider);
        if (!Files.exists(path)) {
            log.warn("repositories.json not found at '{}' — no repositories seeded at startup; " +
                     "repositories will be populated as branch-push webhooks arrive", path.toAbsolutePath());
            return Collections.emptyList();
        }

        String json;
        try {
            json = Files.readString(path);
        } catch (IOException e) {
            log.warn("Cannot read repositories file '{}': {}", path.toAbsolutePath(), e.getMessage());
            return Collections.emptyList();
        }

        FileEntry[] entries = GsonFactory.getInstance().fromJson(json, FileEntry[].class);
        if (entries == null || entries.length == 0) {
            log.debug("repositories.json is empty — no repositories seeded");
            return Collections.emptyList();
        }

        List<RepositoryConfig> result = new ArrayList<>();
        for (FileEntry entry : entries) {
            if (entry.url == null || entry.url.isBlank()) {
                log.warn("Skipping repositories.json entry with missing url: {}", entry.name);
                continue;
            }
            RepositoryConfig config = toConfig(entry);
            if (config == null) {
                log.warn("Skipping repositories.json entry with unparseable url: '{}'", entry.url);
                continue;
            }
            result.add(config);
            log.debug("Loaded repository from file: {}/{} url='{}'",
                    config.getOwner(), config.getRepository(), config.getUrl());
        }
        log.info("Loaded {} repository/repositories from {}", result.size(), path.toAbsolutePath());
        return Collections.unmodifiableList(result);
    }

    // -------------------------------------------------------------------------

    private static Path resolvePath(java.util.function.Function<String, String> envProvider) {
        String fromProperty = System.getProperty("cri.repositories");
        if (fromProperty != null) return Paths.get(fromProperty);
        String fromEnv = envProvider.apply("CRI_REPOSITORIES");
        if (fromEnv != null) return Paths.get(fromEnv);
        return Paths.get("./repositories.json");
    }

    private static RepositoryConfig toConfig(FileEntry entry) {
        try {
            String url = entry.url.trim().replaceAll("\\.git$", "");
            URI uri = URI.create(url);
            String[] parts = uri.getPath().split("/", -1);
            // parts[0] is empty string before the leading '/', parts[1] = owner, parts[2] = repo
            if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) return null;
            return new RepositoryConfig(parts[1], parts[2], url);
        } catch (Exception e) {
            return null;
        }
    }

    /** Gson deserialization model for a single repositories.json entry. */
    private static final class FileEntry {
        String name;
        String url;
    }
}
