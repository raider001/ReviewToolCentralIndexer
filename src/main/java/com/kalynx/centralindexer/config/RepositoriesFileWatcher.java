package com.kalynx.centralindexer.config;

import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.startup.StartupReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Watches {@code repositories.json} for changes and dynamically onboards new repositories.
 *
 * <p>Runs on a dedicated daemon thread. When the file is created or modified:
 * <ol>
 *   <li>Reloads the file through {@link RepositoriesFileLoader} (inherits all existing
 *       sanity checks — invalid URLs, blank entries, parse errors).</li>
 *   <li>Guards against an empty result wiping all known repositories.</li>
 *   <li>Logs a warning for repositories removed from the file (does not delete DB rows).</li>
 *   <li>For each newly added repository: upserts it into the {@code repositories} table,
 *       then runs the same reconciliation path as {@link StartupReconciler}.</li>
 * </ol>
 *
 * <p>A 500 ms debounce absorbs the duplicate {@code ENTRY_MODIFY} events that most
 * editors and OS file systems emit for a single save.
 */
public final class RepositoriesFileWatcher implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RepositoriesFileWatcher.class);
    private static final long DEBOUNCE_MS = 500;

    private final Path filePath;
    private final RepositoriesRepository repositoriesRepository;
    private final BranchRepository branchRepository;
    private final ProviderPlugin plugin;

    private final Set<String> knownRepositories;

    /**
     * Creates a watcher for the given file.
     *
     * @param filePath               absolute, normalised path to {@code repositories.json}
     * @param initialRepos           the repository list already loaded at startup
     * @param repositoriesRepository repository DB operations
     * @param branchRepository       branch DB operations
     * @param plugin                 provider plugin used for reconciliation
     */
    public RepositoriesFileWatcher(Path filePath,
                                    List<RepositoryConfig> initialRepos,
                                    RepositoriesRepository repositoriesRepository,
                                    BranchRepository branchRepository,
                                    ProviderPlugin plugin) {
        this.filePath = filePath.toAbsolutePath().normalize();
        this.repositoriesRepository = repositoriesRepository;
        this.branchRepository = branchRepository;
        this.plugin = plugin;
        this.knownRepositories = initialRepos.stream()
                .map(RepositoryConfig::ownerSlashRepo)
                .collect(Collectors.toCollection(HashSet::new));
    }

    @Override
    public void run() {
        Path dir = filePath.getParent();
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            dir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            log.info("Watching '{}' for repository changes", filePath);

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watcher.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = dir.resolve((Path) event.context()).toAbsolutePath().normalize();
                    if (changed.equals(filePath)) {
                        relevant = true;
                    }
                }
                key.reset();

                if (relevant) {
                    try {
                        Thread.sleep(DEBOUNCE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    onFileChanged();
                }
            }
        } catch (Exception e) {
            log.error("RepositoriesFileWatcher stopped unexpectedly: {}", e.getMessage(), e);
        }
    }

    private void onFileChanged() {
        log.info("repositories.json changed — reloading");
        List<RepositoryConfig> loaded = RepositoriesFileLoader.load();

        if (loaded.isEmpty() && !knownRepositories.isEmpty()) {
            log.warn("Reload produced an empty repository list — ignoring to avoid removing all " +
                     "{} known repositories (check the file for errors)", knownRepositories.size());
            return;
        }

        Set<String> loadedKeys = loaded.stream()
                .map(RepositoryConfig::ownerSlashRepo)
                .collect(Collectors.toSet());

        for (String known : knownRepositories) {
            if (!loadedKeys.contains(known)) {
                log.warn("Repository '{}' was removed from repositories.json — " +
                         "it remains in the database; remove it manually if intended", known);
            }
        }

        for (RepositoryConfig repo : loaded) {
            if (!knownRepositories.contains(repo.ownerSlashRepo())) {
                log.info("New repository detected in repositories.json: '{}' — onboarding",
                        repo.ownerSlashRepo());
                Thread.ofVirtual()
                        .name("repo-onboard-" + repo.ownerSlashRepo())
                        .start(() -> onboardRepository(repo));
            }
        }

        knownRepositories.clear();
        knownRepositories.addAll(loadedKeys);
    }

    private void onboardRepository(RepositoryConfig repo) {
        try {
            repositoriesRepository.upsert(repo.getOwner(), repo.getRepository(), repo.getUrl());
            log.info("Repository '{}' registered in DB", repo.ownerSlashRepo());

            RepositoryRecord record = new RepositoryRecord(
                    repo.getOwner(), repo.getRepository(), repo.getUrl(), null);
            new StartupReconciler(repositoriesRepository, branchRepository, plugin)
                    .reconcileRepository(record);

            log.info("Repository '{}' onboarded successfully", repo.ownerSlashRepo());
        } catch (Exception e) {
            log.error("Failed to onboard repository '{}': {}", repo.ownerSlashRepo(), e.getMessage(), e);
        }
    }
}
