package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.db.RepositoryRecord;

import java.util.function.Consumer;

final class NewRepositoryDiscoverer implements Consumer<RepositoryRecord> {

    private final StartupReconciler reconciler;

    NewRepositoryDiscoverer(StartupReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public void accept(RepositoryRecord record) {
        Thread.ofVirtual()
                .name("repo-discover-" + record.owner() + "/" + record.repository())
                .start(() -> reconciler.reconcileRepository(record));
    }
}
