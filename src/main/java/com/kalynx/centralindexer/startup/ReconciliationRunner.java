package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.IndexerConfig;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.db.RepositoryState;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Runs the per-repository reconciliation phase during application startup.
 *
 * <p>Queries all rows from {@code repository_state} and dispatches one
 * {@link ProviderPlugin#reconcile} call per eligible repository. Two categories are
 * silently skipped:
 * <ul>
 *   <li>Repositories present only in {@code config.repositories} but absent from
 *       {@code repository_state} — no indexer history exists to guide backfill (behaviour 7.5).</li>
 *   <li>Repositories whose {@code last_event_time} predates
 *       {@code now() - indexer.retentionDays} — backfilled events would be pruned
 *       immediately (behaviour 7.6).</li>
 * </ul>
 *
 * <p>Calls are parallelised up to {@code indexer.reconcileConcurrency} simultaneous
 * invocations. Each call is bounded to {@code indexer.reconcileTimeoutSeconds}; calls that
 * exceed the limit are interrupted with a warning and the remaining repositories are still
 * processed (behaviour 7.4).
 */
public final class ReconciliationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);

    private final EventRepository eventRepository;
    private final ProviderPlugin plugin;
    private final int concurrency;
    private final int timeoutSeconds;
    private final int retentionDays;

    /**
     * Constructs a {@code ReconciliationRunner}.
     *
     * @param config          application configuration supplying concurrency and retention settings
     * @param eventRepository used to query repository states
     * @param plugin          the provider plugin whose {@code reconcile} method is invoked
     */
    public ReconciliationRunner(AppConfig config, EventRepository eventRepository, ProviderPlugin plugin) {
        this.eventRepository = eventRepository;
        this.plugin = plugin;
        IndexerConfig indexer = config.getIndexer();
        this.concurrency = indexer.getReconcileConcurrency();
        this.timeoutSeconds = indexer.getReconcileTimeoutSeconds();
        this.retentionDays = indexer.getRetentionDays();
    }

    /**
     * Runs reconciliation for all eligible repositories.
     *
     * <p>Blocks until every reconcile call has either completed or been cancelled due to
     * a per-call timeout.
     *
     * @throws SQLException         if querying repository states fails
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void run() throws SQLException, InterruptedException {
        List<RepositoryState> states = eventRepository.queryRepositoryStates();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        List<RepositoryState> eligible = states.stream()
                .filter(s -> !s.lastEventTime().isBefore(cutoff))
                .collect(Collectors.toList());

        if (eligible.isEmpty()) {
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<?>> futures = new ArrayList<>(eligible.size());

        for (RepositoryState state : eligible) {
            Instant since = state.lastEventTime();
            futures.add(executor.submit(() -> plugin.reconcile(state.repository(), since)));
        }

        executor.shutdown();
        awaitFutures(eligible, futures);
    }

    private void awaitFutures(List<RepositoryState> states, List<Future<?>> futures) {
        for (int i = 0; i < futures.size(); i++) {
            RepositoryState state = states.get(i);
            Future<?> future = futures.get(i);
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("Reconcile for '{}' timed out after {} seconds; slot released",
                        state.repository(), timeoutSeconds);
            } catch (ExecutionException e) {
                log.warn("Reconcile for '{}' failed: {}",
                        state.repository(), e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                break;
            }
        }
    }
}

