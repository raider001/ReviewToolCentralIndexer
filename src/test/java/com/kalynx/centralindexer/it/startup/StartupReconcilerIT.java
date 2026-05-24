package com.kalynx.centralindexer.it.startup;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.spi.WebhookRouter;
import com.kalynx.centralindexer.startup.StartupReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link StartupReconciler} against a real PostgreSQL container.
 *
 * <p>A {@link TrackingPlugin} captures calls to {@link ProviderPlugin#reconcileFromCommit}
 * and {@link ProviderPlugin#reconcileFullReviewTree} without real provider API access.
 * The {@code kalynx-reviews} HEAD is seeded directly into the {@code branches} table
 * (as {@link ProviderPlugin#reconcileAllBranches} would do at runtime).
 */
@RequiresDocker
class StartupReconcilerIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private RepositoriesRepository repo;
    private BranchRepository branchRepository;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = buildPool(container);
        new DatabaseInitializer(pool).init();
        repo = new RepositoriesRepository(pool);
        branchRepository = new BranchRepository(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void doesNothingWhenNoRepositoriesAreRegistered() throws Exception {
        TrackingPlugin plugin = new TrackingPlugin();

        new StartupReconciler(repo, branchRepository, plugin).run();

        assertTrue(plugin.reconcileFromCommitCalls.isEmpty());
    }

    @Test
    void initialisesHeadAndSkipsReconciliationOnFirstRun() throws Exception {
        insertRepository("owner", "repo", "https://example.com");
        insertBranch("owner", "repo", "kalynx-reviews", "livesha1234567");
        TrackingPlugin plugin = new TrackingPlugin();

        new StartupReconciler(repo, branchRepository, plugin).run();

        assertTrue(plugin.reconcileFromCommitCalls.isEmpty(), "reconcileFromCommit must not be called on first run");
        assertTrue(plugin.reconcileFullReviewTreeCalls.contains("owner/repo:livesha1234567"),
                "reconcileFullReviewTree must be called on first run");
        assertEquals("livesha1234567", storedHead("owner", "repo"));
    }

    @Test
    void reconcilesCursorWhenHeadsDiffer() throws Exception {
        insertRepository("owner", "repo", "https://example.com");
        repo.updateKalynxReviewHead("owner", "repo", "oldsha1234567");
        insertBranch("owner", "repo", "kalynx-reviews", "newsha1234567");
        TrackingPlugin plugin = new TrackingPlugin();

        new StartupReconciler(repo, branchRepository, plugin).run();

        assertEquals(1, plugin.reconcileFromCommitCalls.size());
        assertEquals("owner/repo:oldsha1234567:newsha1234567", plugin.reconcileFromCommitCalls.get(0));
        assertEquals("newsha1234567", storedHead("owner", "repo"));
    }

    @Test
    void doesNothingWhenStoredAndLiveHeadsMatch() throws Exception {
        insertRepository("owner", "repo", "https://example.com");
        repo.updateKalynxReviewHead("owner", "repo", "samehead");
        insertBranch("owner", "repo", "kalynx-reviews", "samehead");
        TrackingPlugin plugin = new TrackingPlugin();

        new StartupReconciler(repo, branchRepository, plugin).run();

        assertTrue(plugin.reconcileFromCommitCalls.isEmpty());
        assertEquals("samehead", storedHead("owner", "repo")); // cursor unchanged
    }

    @Test
    void skipsRepositoryWhenKalynxReviewsBranchNotPresent() throws Exception {
        insertRepository("owner", "repo", "https://example.com");
        repo.updateKalynxReviewHead("owner", "repo", "existingsha");
        // No kalynx-reviews branch in branches table → review reconciliation is skipped
        TrackingPlugin plugin = new TrackingPlugin();

        new StartupReconciler(repo, branchRepository, plugin).run();

        assertTrue(plugin.reconcileFromCommitCalls.isEmpty());
        assertEquals("existingsha", storedHead("owner", "repo")); // cursor must not change
    }

    @Test
    void processesAllRepositoriesIndependently() throws Exception {
        insertRepository("owner", "repo-a", "https://example.com/a");
        insertRepository("owner", "repo-b", "https://example.com/b");
        repo.updateKalynxReviewHead("owner", "repo-a", "oldA");
        repo.updateKalynxReviewHead("owner", "repo-b", "oldB");
        insertBranch("owner", "repo-a", "kalynx-reviews", "newA");
        insertBranch("owner", "repo-b", "kalynx-reviews", "newB");
        TrackingPlugin plugin = new TrackingPlugin();

        new StartupReconciler(repo, branchRepository, plugin).run();

        assertEquals(2, plugin.reconcileFromCommitCalls.size());
        assertTrue(plugin.reconcileFromCommitCalls.contains("owner/repo-a:oldA:newA"));
        assertTrue(plugin.reconcileFromCommitCalls.contains("owner/repo-b:oldB:newB"));
        assertEquals("newA", storedHead("owner", "repo-a"));
        assertEquals("newB", storedHead("owner", "repo-b"));
    }

    @Test
    void continuesProcessingOtherReposWhenOnePluginCallFails() throws Exception {
        insertRepository("owner", "repo-a", "https://example.com/a");
        insertRepository("owner", "repo-b", "https://example.com/b");
        repo.updateKalynxReviewHead("owner", "repo-a", "oldA");
        repo.updateKalynxReviewHead("owner", "repo-b", "oldB");
        insertBranch("owner", "repo-a", "kalynx-reviews", "newA");
        insertBranch("owner", "repo-b", "kalynx-reviews", "newB");

        TrackingPlugin plugin = new TrackingPlugin() {
            @Override
            public boolean reconcileFromCommit(String repository, String fromCommit, String toCommit) {
                if (repository.contains("repo-a")) {
                    throw new RuntimeException("simulated provider failure");
                }
                return super.reconcileFromCommit(repository, fromCommit, toCommit);
            }
        };
        plugin.liveHeads.put("owner/repo-b", "newB");

        new StartupReconciler(repo, branchRepository, plugin).run();

        // repo-a failed but repo-b should still have been reconciled
        assertEquals(1, plugin.reconcileFromCommitCalls.size());
        assertEquals("owner/repo-b:oldB:newB", plugin.reconcileFromCommitCalls.get(0));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertRepository(String owner, String repository, String url) throws Exception {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO repositories (owner, repository, url) VALUES (?, ?, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, repository);
            ps.setString(3, url);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private void insertBranch(String owner, String repository, String branchName, String headCommit)
            throws Exception {
        branchRepository.upsert(owner, repository, branchName, headCommit);
    }

    private String storedHead(String owner, String repository) throws Exception {
        return repo.findAll().stream()
                .filter(r -> r.owner().equals(owner) && r.repository().equals(repository))
                .map(RepositoryRecord::kalynxReviewHead)
                .findFirst()
                .orElse(null);
    }

    private ConnectionPool buildPool(PostgresTestContainer c) {
        DatabaseConfig config = GsonFactory.getInstance().fromJson("""
                {
                  "url": "%s",
                  "user": "%s",
                  "password": "%s",
                  "poolSize": 2
                }
                """.formatted(c.getJdbcUrl(), c.getUser(), c.getPassword()),
                DatabaseConfig.class);
        return new ConnectionPool(config);
    }

    // -------------------------------------------------------------------------
    // Fake plugin
    // -------------------------------------------------------------------------

    private static class TrackingPlugin implements ProviderPlugin {

        final List<String> reconcileFromCommitCalls = new ArrayList<>();
        final List<String> reconcileFullReviewTreeCalls = new ArrayList<>();
        final java.util.Map<String, String> liveHeads = new java.util.HashMap<>();

        @Override public String providerId() { return "test"; }
        @Override public void start(ProviderConfig config, EventSink sink, WebhookRouter router) {}
        @Override public void reconcile(String repository, Instant since) {}
        @Override public void stop() {}

        @Override
        public boolean reconcileFromCommit(String repository, String fromCommit, String toCommit) {
            reconcileFromCommitCalls.add(repository + ":" + fromCommit + ":" + toCommit);
            return true;
        }

        @Override
        public boolean reconcileFullReviewTree(String repository, String headCommit) {
            reconcileFullReviewTreeCalls.add(repository + ":" + headCommit);
            return true;
        }
    }
}
