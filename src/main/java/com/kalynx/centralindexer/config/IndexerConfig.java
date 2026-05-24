package com.kalynx.centralindexer.config;

/**
 * Core indexer behaviour configuration.
 */
public final class IndexerConfig {

    private String pluginsDir = "./plugins";

    /**
     * Returns the filesystem path to the directory that plugin JARs are loaded from.
     *
     * @return the plugins directory path (default {@code "./plugins"})
     */
    public String getPluginsDir() {
        return pluginsDir;
    }
}
