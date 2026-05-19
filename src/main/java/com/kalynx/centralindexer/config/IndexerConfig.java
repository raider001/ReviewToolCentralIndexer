package com.kalynx.centralindexer.config;

/**
 * Core indexer behaviour configuration: event retention, plugin directory, reconciliation
 * concurrency, and pruning schedule.
 */
public final class IndexerConfig {

    private int retentionDays = 7;
    private String pluginsDir = "./plugins";
    private int reconcileConcurrency = 50;
    private int reconcileTimeoutSeconds = 10;
    private RetryQueueConfig retryQueue = new RetryQueueConfig();
    private int pruneIntervalHours = 6;

    /**
     * Returns the number of days events are retained before being pruned.
     *
     * @return the retention window in days (default 7)
     */
    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * Returns the filesystem path to the directory that plugin JARs are loaded from.
     * Overridable at runtime via the {@code cri.plugins.dir} system property.
     *
     * @return the plugins directory path (default {@code "./plugins"})
     */
    public String getPluginsDir() {
        return pluginsDir;
    }

    /**
     * Returns the maximum number of concurrent {@code reconcile()} calls during startup.
     *
     * @return the concurrency limit (default 50)
     */
    public int getReconcileConcurrency() {
        return reconcileConcurrency;
    }

    /**
     * Returns the per-repository timeout in seconds for each {@code reconcile()} call.
     * Calls that exceed this limit are interrupted and a warning is logged.
     *
     * @return the timeout in seconds (default 10)
     */
    public int getReconcileTimeoutSeconds() {
        return reconcileTimeoutSeconds;
    }

    /**
     * Returns the retry queue configuration.
     *
     * @return the retry queue config
     */
    public RetryQueueConfig getRetryQueue() {
        return retryQueue;
    }

    /**
     * Returns how often the pruning job runs.
     *
     * @return the pruning interval in hours (default 6)
     */
    public int getPruneIntervalHours() {
        return pruneIntervalHours;
    }
}

