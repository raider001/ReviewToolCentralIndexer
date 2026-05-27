package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.exception.PluginLoadException;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.provider.BuiltInPluginRegistry;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.spi.WebhookRouter;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Discovers, validates, and manages the lifecycle of the single provider plugin.
 *
 * <p>Call order: {@link #load()} → {@link #start(EventSink, WebhookRouter)} → {@link #close()}.
 *
 * <p>The plugins directory is resolved in priority order:
 * <ol>
 *   <li>System property {@code cri.plugins.dir}</li>
 *   <li>{@code indexer.pluginsDir} from the loaded {@link AppConfig}</li>
 * </ol>
 *
 * <p>All JARs in the resolved directory are added to a {@link URLClassLoader} whose parent
 * is the application classloader. {@link ServiceLoader} is then used to discover
 * {@link ProviderPlugin} implementations. Exactly one must be present, and its
 * {@link ProviderPlugin#providerId()} must match {@code config.plugin.providerId}.
 */
public final class PluginLoader implements AutoCloseable {

    static final String SYSTEM_PROPERTY_PLUGINS_DIR = "cri.plugins.dir";

    /**
     * Creates a {@link URLClassLoader} over the given plugins directory.
     */
    @FunctionalInterface
    interface ClassLoaderFactory {
        /**
         * Creates a classloader for the given directory.
         *
         * @param pluginsDir the directory to scan for JARs
         * @return a new {@link URLClassLoader}
         * @throws IOException if the directory cannot be read
         */
        URLClassLoader create(Path pluginsDir) throws IOException;
    }

    /**
     * Discovers {@link ProviderPlugin} instances from a classloader.
     */
    @FunctionalInterface
    interface ServiceDiscovery {
        /**
         * Returns all {@link ProviderPlugin} implementations found in the loader.
         *
         * @param loader the classloader to search
         * @return a list of discovered plugins; may be empty
         */
        List<ProviderPlugin> discover(URLClassLoader loader);
    }

    private final AppConfig config;
    private final MetricsCollector metrics;
    private final ClassLoaderFactory classLoaderFactory;
    private final ServiceDiscovery serviceDiscovery;

    private URLClassLoader classLoader;
    private ProviderPlugin plugin;

    /**
     * Constructs a {@code PluginLoader} using the production classloader and service discovery.
     *
     * @param config  the loaded application configuration
     * @param metrics the metrics collector to inject into built-in plugins; may be {@code null}
     */
    public PluginLoader(AppConfig config, MetricsCollector metrics) {
        this(config, metrics, PluginLoader::buildProductionClassLoader, PluginLoader::discoverViaServiceLoader);
    }

    /** Constructs a {@code PluginLoader} without metrics injection (built-in plugins will not record API calls). */
    public PluginLoader(AppConfig config) {
        this(config, null);
    }

    PluginLoader(AppConfig config, MetricsCollector metrics,
                 ClassLoaderFactory classLoaderFactory, ServiceDiscovery serviceDiscovery) {
        this.config = config;
        this.metrics = metrics;
        this.classLoaderFactory = classLoaderFactory;
        this.serviceDiscovery = serviceDiscovery;
    }

    /**
     * Loads and validates the provider plugin.
     *
     * <p>Resolves the plugins directory, builds a {@link URLClassLoader} over all JARs
     * found there, discovers {@link ProviderPlugin} implementations via {@link ServiceLoader},
     * and validates that exactly one implementation exists with a matching provider ID.
     *
     * @return the loaded and validated plugin
     * @throws PluginLoadException if no plugin is found, more than one is found,
     *                             the provider ID does not match, or the directory cannot be read
     */
    public ProviderPlugin load() {
        Path dir = resolvePluginsDir();
        createClassLoader(dir);
        List<ProviderPlugin> candidates = serviceDiscovery.discover(classLoader);
        if (candidates.isEmpty()) {
            return loadBuiltIn(dir);
        }
        validateSinglePlugin(candidates, dir);
        validateProviderId(candidates.getFirst());
        plugin = candidates.getFirst();
        return plugin;
    }

    private ProviderPlugin loadBuiltIn(Path dir) {
        String providerId = config.getPlugin() != null ? config.getPlugin().getProviderId() : null;
        ProviderPlugin builtIn = BuiltInPluginRegistry.create(providerId, metrics);
        if (builtIn == null) {
            throw new PluginLoadException(
                    "No ProviderPlugin found in '" + dir + "' and no built-in registered for"
                    + " provider '" + providerId + "'");
        }
        plugin = builtIn;
        return plugin;
    }

    /**
     * Starts the loaded plugin by calling {@link ProviderPlugin#start} exactly once.
     *
     * <p>Builds a {@link ProviderConfig} from {@code config.plugin} and
     * {@code config.repositories} and passes it together with the supplied sink and router.
     *
     * @param sink   the event sink the plugin uses to forward events
     * @param router the router the plugin uses to register webhook endpoints
     */
    /**
     * Starts the loaded plugin.
     *
     * @param repositories canonical {@code owner/repo} identifiers to pass to the plugin
     * @param sink         the event sink the plugin uses to forward events
     * @param router       the router the plugin uses to register webhook endpoints
     */
    public void start(List<String> repositories, EventSink sink, WebhookRouter router) {
        ProviderConfig providerConfig = new ProviderConfig(
                config.getPlugin().getProviderId(),
                repositories,
                config.getPlugin().getProperties());
        plugin.start(providerConfig, sink, router);
    }

    /**
     * Stops the plugin and closes the plugin classloader.
     *
     * <p>The {@link URLClassLoader} is always closed — even if {@link ProviderPlugin#stop()}
     * throws a {@link RuntimeException}. If {@code stop()} throws, the exception propagates
     * after the classloader has been closed.
     */
    @Override
    public void close() {
        RuntimeException stopException = null;
        try {
            if (plugin != null) {
                plugin.stop();
            }
        } catch (RuntimeException e) {
            stopException = e;
        } finally {
            closeClassLoaderQuietly();
        }
        if (stopException != null) {
            throw stopException;
        }
    }

    private Path resolvePluginsDir() {
        String sysProp = System.getProperty(SYSTEM_PROPERTY_PLUGINS_DIR);
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }
        return Path.of(config.getIndexer().getPluginsDir());
    }

    private void createClassLoader(Path dir) {
        try {
            classLoader = classLoaderFactory.create(dir);
        } catch (IOException e) {
            throw new PluginLoadException("Failed to initialise plugin classloader from: " + dir, e);
        }
    }

    private void validateSinglePlugin(List<ProviderPlugin> candidates, Path dir) {
        if (candidates.isEmpty()) {
            throw new PluginLoadException("No ProviderPlugin implementation found in: " + dir);
        }
        if (candidates.size() > 1) {
            String names = candidates.stream()
                    .map(p -> p.getClass().getName())
                    .collect(Collectors.joining(", "));
            throw new PluginLoadException("Multiple ProviderPlugin implementations found: " + names);
        }
    }

    private void validateProviderId(ProviderPlugin candidate) {
        String expected = config.getPlugin().getProviderId();
        String actual = candidate.providerId();
        if (!expected.equals(actual)) {
            throw new PluginLoadException(
                    "Plugin ID mismatch: config expects '" + expected +
                    "' but plugin declares '" + actual + "'");
        }
    }

    private void closeClassLoaderQuietly() {
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static URLClassLoader buildProductionClassLoader(Path pluginsDir) throws IOException {
        if (!Files.isDirectory(pluginsDir)) {
            return new URLClassLoader(new URL[0], PluginLoader.class.getClassLoader());
        }
        URL[] urls;
        try (var stream = Files.list(pluginsDir)) {
            urls = stream
                    .filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> {
                        try {
                            return p.toUri().toURL();
                        } catch (MalformedURLException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toArray(URL[]::new);
        }
        return new URLClassLoader(urls, PluginLoader.class.getClassLoader());
    }

    private static List<ProviderPlugin> discoverViaServiceLoader(URLClassLoader loader) {
        List<ProviderPlugin> plugins = new ArrayList<>();
        ServiceLoader.load(ProviderPlugin.class, loader).forEach(plugins::add);
        return plugins;
    }
}

