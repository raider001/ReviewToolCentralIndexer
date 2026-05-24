package com.kalynx.centralindexer.gui;

import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.gui.panel.QueryPanel;
import com.kalynx.centralindexer.gui.panel.StatisticsPanel;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.swingtheme.themedcomponents.ThemedFrame;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Optional GUI window for the Central Indexer, launched with the {@code --gui} flag.
 *
 * <p>The dashboard (statistics + charts) is the default view. The Kalynx slide-out menu
 * provides navigation to per-table query panels for Reviews, Branches, and Repositories.
 */
public final class IndexerGui extends ThemedFrame {

    private final MetricsCollector         metrics;
    private final ReviewsIndexRepository   reviewsRepository;
    private final BranchRepository         branchRepository;
    private final RepositoriesRepository   repositoriesRepository;

    private StatisticsPanel statisticsPanel;
    private ThemedPanel     currentPanel;

    // Lazily created — one QueryPanel per table name
    private final Map<String, QueryPanel> queryPanels = new HashMap<>();

    public IndexerGui(MetricsCollector metrics,
                      ReviewsIndexRepository reviewsRepository,
                      BranchRepository branchRepository,
                      RepositoriesRepository repositoriesRepository) {
        super("Central Indexer", 1100, 720);
        this.metrics               = metrics;
        this.reviewsRepository     = reviewsRepository;
        this.branchRepository      = branchRepository;
        this.repositoriesRepository = repositoriesRepository;
        buildUi();
    }

    private void buildUi() {
        setMenuItems(
            new MenuItem("Dashboard",    this::showDashboard),
            new MenuItem("Reviews",      () -> showQueryPanel("Reviews")),
            new MenuItem("Branches",     () -> showQueryPanel("Branches")),
            new MenuItem("Repositories", () -> showQueryPanel("Repositories"))
        );

        statisticsPanel = new StatisticsPanel(metrics);
        contentPanel.setLayout(new BorderLayout());
        showDashboard();
    }

    private void showDashboard() {
        switchTo(statisticsPanel);
        setWindowTitle("Central Indexer");
    }

    private void showQueryPanel(String table) {
        QueryPanel qp = queryPanels.computeIfAbsent(table, t -> {
            QueryPanel panel = new QueryPanel(reviewsRepository, branchRepository, repositoriesRepository);
            panel.selectTable(t);
            return panel;
        });
        switchTo(qp);
        setWindowTitle("Central Indexer — " + table);
    }

    private void switchTo(ThemedPanel panel) {
        if (currentPanel != null) contentPanel.remove(currentPanel);
        currentPanel = panel;
        contentPanel.add(currentPanel, BorderLayout.CENTER);
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    /** Starts the dashboard refresh timer. Call after {@link #setVisible(boolean)}. */
    public void startRefresh() {
        statisticsPanel.startRefresh();
    }

    /** Stops the dashboard refresh timer. */
    public void stopRefresh() {
        statisticsPanel.stopRefresh();
    }
}
