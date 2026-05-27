package com.kalynx.centralindexer.provider;

import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.provider.bitbucket.BitbucketPlugin;
import com.kalynx.centralindexer.provider.github.GitHubPlugin;
import com.kalynx.centralindexer.provider.gitlab.GitLabPlugin;
import com.kalynx.centralindexer.spi.ProviderPlugin;

import java.util.Map;
import java.util.function.Function;

/**
 * Registry of provider plugins compiled directly into the indexer.
 *
 * <p>Built-in plugins are used when no external plugin JAR is found in the configured
 * plugins directory. The active provider is selected by matching
 * {@code config.plugin.providerId} against the keys in this registry.
 *
 * <p>Supported built-in provider IDs:
 * <ul>
 *   <li>{@code github} — {@link GitHubPlugin}</li>
 *   <li>{@code bitbucket} — {@link BitbucketPlugin}</li>
 *   <li>{@code gitlab} — {@link GitLabPlugin}</li>
 * </ul>
 *
 * <p>To override any built-in, place a JAR implementing {@link ProviderPlugin} in the
 * configured plugins directory. External JARs always take precedence.
 */
public final class BuiltInPluginRegistry {

    private static final Map<String, Function<MetricsCollector, ProviderPlugin>> REGISTRY = Map.of(
            "github",    m -> new GitHubPlugin(m),
            "bitbucket", m -> new BitbucketPlugin(m),
            "gitlab",    m -> new GitLabPlugin(m)
    );

    private BuiltInPluginRegistry() {
    }

    /**
     * Returns a new instance of the built-in plugin for the given provider ID,
     * or {@code null} if no built-in matches.
     *
     * @param providerId the provider identifier from {@code config.plugin.providerId}
     * @param metrics    the metrics collector to inject; may be {@code null}
     * @return a fresh {@link ProviderPlugin} instance, or {@code null}
     */
    public static ProviderPlugin create(String providerId, MetricsCollector metrics) {
        if (providerId == null) {
            return null;
        }
        Function<MetricsCollector, ProviderPlugin> factory = REGISTRY.get(providerId);
        return factory != null ? factory.apply(metrics) : null;
    }

    /** Convenience overload with no metrics injection. */
    public static ProviderPlugin create(String providerId) {
        return create(providerId, null);
    }

    /**
     * Returns {@code true} if the given provider ID corresponds to a built-in plugin.
     *
     * @param providerId the provider identifier to check
     * @return {@code true} if a built-in implementation is registered
     */
    public static boolean isBuiltIn(String providerId) {
        return providerId != null && REGISTRY.containsKey(providerId);
    }
}

