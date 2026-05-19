package com.kalynx.centralindexer.config;

import java.util.Collections;
import java.util.List;

/**
 * Top-level application configuration deserialised from {@code config.json}.
 *
 * <p>Gson maps each JSON field name directly to the corresponding Java field.
 * {@code ${ENV_VAR}} placeholders in the raw JSON are resolved by
 * {@link ConfigLoader} before deserialisation.
 */
public final class AppConfig {

    private ServerConfig server = new ServerConfig();
    private AuthConfig auth = new AuthConfig();
    private DatabaseConfig database;
    private IndexerConfig indexer = new IndexerConfig();
    private PluginSettings plugin;
    private List<String> repositories = Collections.emptyList();

    /**
     * Returns the HTTP server configuration.
     *
     * @return the server config
     */
    public ServerConfig getServer() {
        return server;
    }

    /**
     * Returns the Bearer token authentication configuration.
     *
     * @return the auth config
     */
    public AuthConfig getAuth() {
        return auth;
    }

    /**
     * Returns the PostgreSQL connection configuration.
     *
     * @return the database config
     */
    public DatabaseConfig getDatabase() {
        return database;
    }

    /**
     * Returns the core indexer behaviour configuration.
     *
     * @return the indexer config
     */
    public IndexerConfig getIndexer() {
        return indexer;
    }

    /**
     * Returns the raw plugin block from config, used to construct
     * {@link com.kalynx.centralindexer.spi.ProviderConfig}.
     *
     * @return the plugin settings
     */
    public PluginSettings getPlugin() {
        return plugin;
    }

    /**
     * Returns the list of canonical repository identifiers ({@code owner/repo}) that
     * the plugin is responsible for reconciling at startup.
     *
     * @return an immutable list of repository names
     */
    public List<String> getRepositories() {
        return repositories == null ? Collections.emptyList() : Collections.unmodifiableList(repositories);
    }
}

