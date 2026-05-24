package com.kalynx.centralindexer.plugin;
import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.exception.PluginLoadException;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.spi.WebhookRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * Unit tests for {@link PluginLoader}.
 */
class PluginLoaderTest {
    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(PluginLoader.SYSTEM_PROPERTY_PLUGINS_DIR);
    }
    @Test
    void throwsWhenNoPlugin() {
        URLClassLoader loader = mock(URLClassLoader.class);
        PluginLoader pluginLoader = new PluginLoader(config("test"), dir -> loader, l -> List.of());
        assertThrows(PluginLoadException.class, pluginLoader::load);
    }
    @Test
    void throwsWhenMultiplePlugins() {
        ProviderPlugin p1 = pluginWithId("github");
        ProviderPlugin p2 = pluginWithId("github");
        URLClassLoader loader = mock(URLClassLoader.class);
        PluginLoader pluginLoader = new PluginLoader(config("github"), dir -> loader, l -> List.of(p1, p2));
        PluginLoadException ex = assertThrows(PluginLoadException.class, pluginLoader::load);
        assertTrue(ex.getMessage().contains(p1.getClass().getName()),
                "Exception should list conflicting class names");
    }
    @Test
    void throwsOnProviderIdMismatch() {
        ProviderPlugin plugin = pluginWithId("bitbucket");
        URLClassLoader loader = mock(URLClassLoader.class);
        PluginLoader pluginLoader = new PluginLoader(config("github"), dir -> loader, l -> List.of(plugin));
        PluginLoadException ex = assertThrows(PluginLoadException.class, pluginLoader::load);
        assertTrue(ex.getMessage().contains("github"),  "Exception should show expected ID");
        assertTrue(ex.getMessage().contains("bitbucket"), "Exception should show actual ID");
    }
    @Test
    void pluginsDirSystemPropertyOverridesConfig() throws Exception {
        System.setProperty(PluginLoader.SYSTEM_PROPERTY_PLUGINS_DIR, "/custom/plugins");
        AtomicReference<Path> capturedDir = new AtomicReference<>();
        PluginLoader pluginLoader = new PluginLoader(config("test"), dir -> {
            capturedDir.set(dir);
            return mock(URLClassLoader.class);
        }, l -> List.of(pluginWithId("test")));
        pluginLoader.load();
        assertEquals(Path.of("/custom/plugins"), capturedDir.get(),
                "System property should override config plugins dir");
    }
    @Test
    void startCallsPluginStartExactlyOnce() throws Exception {
        ProviderPlugin plugin = mock(ProviderPlugin.class);
        when(plugin.providerId()).thenReturn("test");
        URLClassLoader loader = mock(URLClassLoader.class);
        PluginLoader pluginLoader = new PluginLoader(config("test"), dir -> loader, l -> List.of(plugin));
        pluginLoader.load();
        pluginLoader.start(List.of(), mock(EventSink.class), mock(WebhookRouter.class));
        verify(plugin, times(1)).start(any(), any(), any());
    }
    @Test
    void closeAlwaysClosesClassLoaderEvenWhenStopThrows() throws Exception {
        ProviderPlugin plugin = mock(ProviderPlugin.class);
        when(plugin.providerId()).thenReturn("test");
        doThrow(new RuntimeException("stop failed")).when(plugin).stop();
        URLClassLoader loader = mock(URLClassLoader.class);
        PluginLoader pluginLoader = new PluginLoader(config("test"), dir -> loader, l -> List.of(plugin));
        pluginLoader.load();
        assertThrows(RuntimeException.class, pluginLoader::close);
        verify(loader).close();
    }
    private ProviderPlugin pluginWithId(String id) {
        return new ProviderPlugin() {
            public String providerId() { return id; }
            public void start(com.kalynx.centralindexer.spi.ProviderConfig c,
                              com.kalynx.centralindexer.spi.EventSink s,
                              com.kalynx.centralindexer.spi.WebhookRouter r) {}
            public void reconcile(String repo, Instant since) {}
            public void stop() {}
        };
    }
    private AppConfig config(String providerId) {
        String json = """
                {
                  "plugin": { "providerId": "%s", "properties": {} },
                  "indexer": { "pluginsDir": "./plugins" },
                  "repositories": []
                }
                """.formatted(providerId);
        return GsonFactory.getInstance().fromJson(json, AppConfig.class);
    }
}