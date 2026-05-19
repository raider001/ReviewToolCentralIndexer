package com.kalynx.centralindexer.config;

import java.util.Collections;
import java.util.Map;

/**
 * Raw JSON representation of the {@code "plugin"} block in {@code config.json}.
 *
 * <p>This class is populated by Gson during config deserialisation. The indexer
 * combines it with the top-level {@code "repositories"} list to build the
 * {@link com.kalynx.centralindexer.spi.ProviderConfig} passed to
 * {@link com.kalynx.centralindexer.spi.ProviderPlugin#start}.
 */
public final class PluginSettings {

    private String providerId;
    private Map<String, String> properties = Collections.emptyMap();

    /**
     * Returns the expected provider identifier, validated against
     * {@link com.kalynx.centralindexer.spi.ProviderPlugin#providerId()} at startup.
     *
     * @return the provider ID (e.g. {@code "github"})
     */
    public String getProviderId() {
        return providerId;
    }

    /**
     * Returns provider-specific key-value settings (API tokens, webhook secrets, etc.)
     * that are forwarded to the plugin unchanged.
     *
     * @return an immutable map of plugin properties
     */
    public Map<String, String> getProperties() {
        return properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(properties);
    }
}

