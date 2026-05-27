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
    void create_githubProviderId_returnsGitHubPlugin() {
        ProviderPlugin plugin = BuiltInPluginRegistry.create("github");
        assertNotNull(plugin);
        assertInstanceOf(GitHubPlugin.class, plugin);
        assertEquals("github", plugin.providerId());
    }

    @Test
    void create_bitbucketProviderId_returnsBitbucketPlugin() {
        ProviderPlugin plugin = BuiltInPluginRegistry.create("bitbucket");
        assertNotNull(plugin);
        assertInstanceOf(BitbucketPlugin.class, plugin);
        assertEquals("bitbucket", plugin.providerId());
    }

    @Test
    void create_gitlabProviderId_returnsGitLabPlugin() {
        ProviderPlugin plugin = BuiltInPluginRegistry.create("gitlab");
        assertNotNull(plugin);
        assertInstanceOf(GitLabPlugin.class, plugin);
        assertEquals("gitlab", plugin.providerId());
    }

    @Test
    void create_unknownProvider_returnsNull() {
        assertNull(BuiltInPluginRegistry.create("unknown-provider"));
    }

    @Test
    void create_nullProviderId_returnsNull() {
        assertNull(BuiltInPluginRegistry.create(null));
    }

    @Test
    void isBuiltIn_knownProvider_returnsTrue() {
        assertTrue(BuiltInPluginRegistry.isBuiltIn("github"));
        assertTrue(BuiltInPluginRegistry.isBuiltIn("bitbucket"));
        assertTrue(BuiltInPluginRegistry.isBuiltIn("gitlab"));
    }

    @Test
    void isBuiltIn_unknownProvider_returnsFalse() {
        assertFalse(BuiltInPluginRegistry.isBuiltIn("custom-provider"));
        assertFalse(BuiltInPluginRegistry.isBuiltIn(null));
    }

    @Test
    void create_calledTwice_returnsDifferentInstances() {
        ProviderPlugin a = BuiltInPluginRegistry.create("github");
        ProviderPlugin b = BuiltInPluginRegistry.create("github");
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "Each call must return a fresh instance");
    }
}

