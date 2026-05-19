package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.db.RepositoryState;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReconciliationRunner}.
 */
class ReconciliationRunnerTest {

    @Test
    void callsReconcileForEachRepoStateRow() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        ProviderPlugin plugin = mock(ProviderPlugin.class);
        Instant now = Instant.now();
        when(repo.queryRepositoryStates()).thenReturn(List.of(
                new RepositoryState("owner/a", 1L, now),
                new RepositoryState("owner/b", 1L, now)));

        new ReconciliationRunner(buildConfig(2, 10, 7), repo, plugin).run();

        verify(plugin).reconcile("owner/a", now);
        verify(plugin).reconcile("owner/b", now);
    }

    @Test
    void concurrencyBoundRespected() throws Exception {
        int concurrency = 2;
        int repoCount = 4;
        AtomicInteger activeCount = new AtomicInteger();
        AtomicInteger peakCount = new AtomicInteger();

        EventRepository repo = mock(EventRepository.class);
        ProviderPlugin plugin = mock(ProviderPlugin.class);
        Instant now = Instant.now();

        List<RepositoryState> states = new ArrayList<>();
        for (int i = 0; i < repoCount; i++) {
            states.add(new RepositoryState("owner/repo" + i, 1L, now));
        }
        when(repo.queryRepositoryStates()).thenReturn(states);

        Answer<Void> trackConcurrency = invocation -> {
            int current = activeCount.incrementAndGet();
            peakCount.updateAndGet(peak -> Math.max(peak, current));
            Thread.sleep(150);
            activeCount.decrementAndGet();
            return null;
        };
        doAnswer(trackConcurrency).when(plugin).reconcile(anyString(), any());

        new ReconciliationRunner(buildConfig(concurrency, 5, 7), repo, plugin).run();

        assertTrue(peakCount.get() <= concurrency,
                "Peak concurrent calls must not exceed " + concurrency + ", was " + peakCount.get());
    }

    @Test
    void timeoutInterruptsSlowReconcile() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        ProviderPlugin plugin = mock(ProviderPlugin.class);
        Instant now = Instant.now();
        AtomicInteger fastCallCount = new AtomicInteger();

        when(repo.queryRepositoryStates()).thenReturn(List.of(
                new RepositoryState("owner/slow", 1L, now),
                new RepositoryState("owner/fast", 1L, now)));

        doAnswer(inv -> {
            if ("owner/slow".equals(inv.getArgument(0))) {
                Thread.sleep(60_000);
            } else {
                fastCallCount.incrementAndGet();
            }
            return null;
        }).when(plugin).reconcile(anyString(), any());

        long start = System.currentTimeMillis();
        new ReconciliationRunner(buildConfig(2, 1, 7), repo, plugin).run();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 10_000, "Runner must complete well under 10 s, took " + elapsed + " ms");
        assertEquals(1, fastCallCount.get(), "Fast repository must still be reconciled despite slow sibling");
    }

    @Test
    void reposAbsentFromStateSkipped() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        ProviderPlugin plugin = mock(ProviderPlugin.class);
        Instant now = Instant.now();
        when(repo.queryRepositoryStates()).thenReturn(
                List.of(new RepositoryState("owner/in-state", 1L, now)));

        AppConfig config = buildConfigWithRepos(2, 10, 7,
                List.of("owner/in-state", "owner/only-in-config"));
        new ReconciliationRunner(config, repo, plugin).run();

        verify(plugin).reconcile("owner/in-state", now);
        verify(plugin, never()).reconcile(eq("owner/only-in-config"), any());
    }

    @Test
    void reposOlderThanRetentionWindowSkipped() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        ProviderPlugin plugin = mock(ProviderPlugin.class);
        Instant recentTime = Instant.now();
        Instant oldTime = Instant.now().minus(10, ChronoUnit.DAYS);

        when(repo.queryRepositoryStates()).thenReturn(List.of(
                new RepositoryState("owner/recent", 1L, recentTime),
                new RepositoryState("owner/old", 1L, oldTime)));

        new ReconciliationRunner(buildConfig(2, 10, 7), repo, plugin).run();

        verify(plugin).reconcile("owner/recent", recentTime);
        verify(plugin, never()).reconcile(eq("owner/old"), any());
    }

    private AppConfig buildConfig(int concurrency, int timeoutSeconds, int retentionDays) {
        return buildConfigWithRepos(concurrency, timeoutSeconds, retentionDays, List.of());
    }

    private AppConfig buildConfigWithRepos(int concurrency, int timeoutSeconds, int retentionDays,
                                           List<String> repos) {
        String repoJson = repos.isEmpty() ? "[]"
                : repos.stream().map(r -> "\"" + r + "\"").collect(Collectors.joining(",", "[", "]"));
        String json = String.format(
                "{\"indexer\":{\"reconcileConcurrency\":%d,\"reconcileTimeoutSeconds\":%d,\"retentionDays\":%d},"
                + "\"repositories\":%s}",
                concurrency, timeoutSeconds, retentionDays, repoJson);
        return GsonFactory.getInstance().fromJson(json, AppConfig.class);
    }
}

