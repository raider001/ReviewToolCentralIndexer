package com.kalynx.centralindexer.startup;

/**
 * Common contract for startup reconciliation steps.
 *
 * <p>Each implementation is responsible for a single well-defined concern
 * (branch heads, review refs, comment changes, etc.). The coordinator
 * {@link StartupReconciler} iterates and calls {@link #reconcile()} on each.
 */
public interface Reconciler {
    void reconcile();
}
