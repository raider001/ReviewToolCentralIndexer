package com.kalynx.centralindexer.it.plugin;
import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.plugin.PluginLoader;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
/**
 * Integration tests for {@link PluginLoader} using a real dynamically compiled JAR.
 *
 * <p>These tests do not require Docker - the {@link RequiresDocker} annotation is
 * absent intentionally. The only dependency is a JDK with {@code javac} available
 * via {@link javax.tools.ToolProvider#getSystemJavaCompiler()}.
 */
class PluginLoaderIT {
    @TempDir
    Path tempDir;
    @Test
    void load_realJarFile_returnsPluginWithCorrectProviderId() throws Exception {
        Path pluginsDir = buildMinimalPluginJar("test-provider", tempDir);
        AppConfig config = buildConfig("test-provider", pluginsDir.toString());
        PluginLoader loader = new PluginLoader(config);
        ProviderPlugin plugin = loader.load();
        assertNotNull(plugin, "Loaded plugin must not be null");
        assertEquals("test-provider", plugin.providerId(),
                "Plugin providerId must match config");
        loader.start(List.of(), mock(com.kalynx.centralindexer.spi.EventSink.class), new WebhookRouterImpl());
        loader.close();
    }
    private Path buildMinimalPluginJar(String providerId, Path root) throws Exception {
        Path srcDir = root.resolve("src");
        Path classesDir = root.resolve("classes");
        Path pluginsDir = root.resolve("plugins");
        Files.createDirectories(srcDir);
        Files.createDirectories(classesDir);
        Files.createDirectories(pluginsDir);
        String spiPkg = "com.kalynx.centralindexer.spi";
        Path srcFile = srcDir.resolve("MinimalPlugin.java");
        Files.writeString(srcFile, """
                import %s.ProviderPlugin;
                import %s.ProviderConfig;
                import %s.EventSink;
                import %s.WebhookRouter;
                import java.time.Instant;
                public class MinimalPlugin implements ProviderPlugin {
                    public String providerId() { return "%s"; }
                    public void start(ProviderConfig c, EventSink s, WebhookRouter r) {}
                    public void reconcile(String repo, Instant since) {}
                    public void stop() {}
                }
                """.formatted(spiPkg, spiPkg, spiPkg, spiPkg, providerId));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javac must be available (requires JDK, not just JRE)");
        int result = compiler.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                srcFile.toString());
        assertEquals(0, result, "Plugin source compilation must succeed");
        Path jarFile = pluginsDir.resolve("minimal-plugin.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarFile))) {
            jos.putNextEntry(new JarEntry("META-INF/services/" + ProviderPlugin.class.getName()));
            jos.write("MinimalPlugin\n".getBytes());
            jos.closeEntry();
            Path classFile = classesDir.resolve("MinimalPlugin.class");
            jos.putNextEntry(new JarEntry("MinimalPlugin.class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return pluginsDir;
    }
    private AppConfig buildConfig(String providerId, String pluginsDir) {
        String json = """
                {
                  "plugin": { "providerId": "%s", "properties": {} },
                  "indexer": { "pluginsDir": "%s" },
                  "repositories": []
                }
                """.formatted(providerId, pluginsDir.replace("\\", "\\\\"));
        return GsonFactory.getInstance().fromJson(json, AppConfig.class);
    }
}