package com.kalynx.centralindexer.provider;

import com.kalynx.centralindexer.provider.bitbucket.BitbucketPlugin;
import com.kalynx.centralindexer.provider.github.GitHubPlugin;
import com.kalynx.centralindexer.provider.gitlab.GitLabPlugin;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BuiltInPluginRegistry}.
 */
class BuiltInPluginRegistryTest {

    @Test
    void createsGitHubPlugin() {
        ProviderPlugin plugin = BuiltInPluginRegistry.create("github");
        assertNotNull(plugin);
        assertInstanceOf(GitHubPlugin.class, plugin);
        assertEquals("github", plugin.providerId());
    }

    @Test
    void createsBitbucketPlugin() {
        ProviderPlugin plugin = BuiltInPluginRegistry.create("bitbucket");
        assertNotNull(plugin);
        assertInstanceOf(BitbucketPlugin.class, plugin);
        assertEquals("bitbucket", plugin.providerId());
    }

    @Test
    void createsGitLabPlugin() {
        ProviderPlugin plugin = BuiltInPluginRegistry.create("gitlab");
        assertNotNull(plugin);
        assertInstanceOf(GitLabPlugin.class, plugin);
        assertEquals("gitlab", plugin.providerId());
    }

    @Test
    void returnsNullForUnknownProvider() {
        assertNull(BuiltInPluginRegistry.create("unknown-provider"));
    }

    @Test
    void returnsNullForNullProviderId() {
        assertNull(BuiltInPluginRegistry.create(null));
    }

    @Test
    void isBuiltInReturnsTrueForKnownProviders() {
        assertTrue(BuiltInPluginRegistry.isBuiltIn("github"));
        assertTrue(BuiltInPluginRegistry.isBuiltIn("bitbucket"));
        assertTrue(BuiltInPluginRegistry.isBuiltIn("gitlab"));
    }

    @Test
    void isBuiltInReturnsFalseForUnknown() {
        assertFalse(BuiltInPluginRegistry.isBuiltIn("custom-provider"));
        assertFalse(BuiltInPluginRegistry.isBuiltIn(null));
    }

    @Test
    void eachCallCreatesNewInstance() {
        ProviderPlugin a = BuiltInPluginRegistry.create("github");
        ProviderPlugin b = BuiltInPluginRegistry.create("github");
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "Each call must return a fresh instance");
    }
}

