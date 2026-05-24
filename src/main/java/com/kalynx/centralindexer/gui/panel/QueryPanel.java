package com.kalynx.centralindexer.gui.panel;

import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.BranchRecord;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.db.ReviewRecord;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedComboBox;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedScrollPane;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Query Tool tab for the indexer GUI.
 *
 * <p>Provides a table selector, per-table filter controls, and a results {@link JTable}.
 * All database access runs on a virtual thread so the EDT is never blocked.
 */
public final class QueryPanel extends ThemedPanel {

    private static final Logger log = LoggerFactory.getLogger(QueryPanel.class);

    // --- table names -------------------------------------------------------------
    private static final String TABLE_REVIEWS       = "Reviews";
    private static final String TABLE_BRANCHES      = "Branches";
    private static final String TABLE_REPOSITORIES  = "Repositories";

    private static final String[] TABLES = { TABLE_REVIEWS, TABLE_BRANCHES, TABLE_REPOSITORIES };

    // --- deps --------------------------------------------------------------------
    private final ReviewsIndexRepository reviewsRepo;
    private final BranchRepository branchRepo;
    private final RepositoriesRepository reposRepo;
    private final ThemeManager tm = ThemeManager.getInstance();

    // --- UI ----------------------------------------------------------------------
    private final ThemedComboBox<String> tableCombo;
    private final JPanel filterCards; // CardLayout panel, one card per table
    private final DefaultTableModel tableModel;
    private final JTable resultsTable;
    private final ThemedLabel statusLabel;

    // reviews filters
    private final ThemedComboBox<String> reviewsWindowCombo;
    private final ThemedTextField reviewsStatusField;

    // branches filters
    private final ThemedTextField branchPrefixField;
    private final ThemedTextField branchOwnerField;
    private final ThemedTextField branchRepoField;

    // -------------------------------------------------------------------------

    public QueryPanel(ReviewsIndexRepository reviewsRepo,
                      BranchRepository branchRepo,
                      RepositoriesRepository reposRepo) {
        super(new MigLayout("insets 12, gap 8, flowy", "[grow]", "[][][][grow][]"));
        this.reviewsRepo = reviewsRepo;
        this.branchRepo  = branchRepo;
        this.reposRepo   = reposRepo;

        // --- selector row -------------------------------------------------------
        ThemedPanel selectorRow = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        selectorRow.add(new ThemedLabel("Table:"));
        tableCombo = new ThemedComboBox<>();
        for (String t : TABLES) tableCombo.addItem(t);
        selectorRow.add(tableCombo);
        add(selectorRow, "growx");

        // --- filter cards -------------------------------------------------------
        reviewsWindowCombo = new ThemedComboBox<>();
        reviewsWindowCombo.addItem("All time");
        reviewsWindowCombo.addItem("Last 24 hours");
        reviewsWindowCombo.addItem("Last 7 days");
        reviewsWindowCombo.addItem("Last 30 days");
        reviewsStatusField = new ThemedTextField();
        reviewsStatusField.setColumns(16);

        branchPrefixField = new ThemedTextField();
        branchPrefixField.setColumns(20);
        branchOwnerField  = new ThemedTextField();
        branchOwnerField.setColumns(12);
        branchRepoField   = new ThemedTextField();
        branchRepoField.setColumns(12);

        filterCards = new JPanel(new CardLayout());
        filterCards.setOpaque(false);
        filterCards.add(buildReviewsFilters(),      TABLE_REVIEWS);
        filterCards.add(buildBranchesFilters(),     TABLE_BRANCHES);
        filterCards.add(buildRepositoriesFilters(), TABLE_REPOSITORIES);
        add(filterCards, "growx");

        // --- action row ---------------------------------------------------------
        ThemedPanel actionRow = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ThemedButton runBtn = new ThemedButton("Run Query");
        runBtn.addActionListener(e -> runQuery());
        actionRow.add(runBtn);
        statusLabel = new ThemedLabel(" ");
        statusLabel.setFont(tm.getBaseFont().deriveFont(Font.ITALIC, tm.scale(11)));
        actionRow.add(statusLabel);
        add(actionRow, "growx");

        // --- results table ------------------------------------------------------
        tableModel = new DefaultTableModel() {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        resultsTable = buildTable();
        ThemedScrollPane scroll = new ThemedScrollPane(resultsTable);
        add(scroll, "growx, growy");

        // switch filter cards when table selector changes
        tableCombo.addActionListener(e -> {
            CardLayout cl = (CardLayout) filterCards.getLayout();
            String sel = (String) tableCombo.getSelectedItem();
            if (sel != null) cl.show(filterCards, sel);
        });
    }

    /** Pre-selects the given table; triggers the filter card switch via the combo's listener. */
    public void selectTable(String tableName) {
        for (int i = 0; i < TABLES.length; i++) {
            if (TABLES[i].equals(tableName)) {
                tableCombo.setSelectedIndex(i);
                break;
            }
        }
    }

    // --- filter card builders ---------------------------------------------------

    private JPanel buildReviewsFilters() {
        ThemedPanel p = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(new ThemedLabel("Since:"));
        p.add(reviewsWindowCombo);
        p.add(new ThemedLabel("Status (csv):"));
        p.add(reviewsStatusField);
        return p;
    }

    private JPanel buildBranchesFilters() {
        ThemedPanel p = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(new ThemedLabel("Branch prefix:"));
        p.add(branchPrefixField);
        p.add(new ThemedLabel("Owner:"));
        p.add(branchOwnerField);
        p.add(new ThemedLabel("Repository:"));
        p.add(branchRepoField);
        return p;
    }

    private JPanel buildRepositoriesFilters() {
        ThemedPanel p = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(new ThemedLabel("No filters — shows all tracked repositories."));
        return p;
    }

    // --- table ------------------------------------------------------------------

    private JTable buildTable() {
        JTable t = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int col) {
                Component c = super.prepareRenderer(renderer, row, col);
                if (!isRowSelected(row)) {
                    Color bg = (row % 2 == 0)
                            ? tm.getCurrentTheme().getBackgroundColor()
                            : tm.getCurrentTheme().getButtonBackground();
                    c.setBackground(bg);
                }
                c.setForeground(tm.getCurrentTheme().getForegroundColor());
                return c;
            }
        };
        t.setFont(tm.getBaseFont().deriveFont(Font.PLAIN, tm.scale(12)));
        t.getTableHeader().setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(12)));
        t.setRowHeight(tm.scale(24));
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        t.setFillsViewportHeight(true);
        t.setOpaque(true);
        applyTableTheme(t);
        tm.addThemeChangeListener(() -> applyTableTheme(t));
        return t;
    }

    private void applyTableTheme(JTable t) {
        Theme theme = tm.getCurrentTheme();
        t.setBackground(theme.getBackgroundColor());
        t.setForeground(theme.getForegroundColor());
        t.setSelectionBackground(theme.getAccentColor());
        t.setSelectionForeground(theme.getButtonForeground());
        t.setGridColor(theme.getBorderColor());
        // Custom renderer so column header colors are not overridden by the L&F.
        t.getTableHeader().setDefaultRenderer((table, value, isSelected, hasFocus, row, col) -> {
            Theme th = tm.getCurrentTheme();
            JLabel lbl = new JLabel(value == null ? "" : value.toString());
            lbl.setOpaque(true);
            lbl.setBackground(th.getBorderColor());
            lbl.setForeground(th.getForegroundColor());
            lbl.setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(12)));
            lbl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, th.getBorderColor().darker()),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)
            ));
            return lbl;
        });
    }

    // --- query dispatch ---------------------------------------------------------

    private void runQuery() {
        String selected = (String) tableCombo.getSelectedItem();
        if (selected == null) return;
        setStatus("Running…");
        Thread.ofVirtual().name("gui-query").start(() -> {
            try {
                switch (selected) {
                    case TABLE_REVIEWS      -> queryReviews();
                    case TABLE_BRANCHES     -> queryBranches();
                    case TABLE_REPOSITORIES -> queryRepositories();
                }
            } catch (Exception ex) {
                log.warn("GUI query failed", ex);
                SwingUtilities.invokeLater(() -> setStatus("Error: " + ex.getMessage()));
            }
        });
    }

    // --- reviews ----------------------------------------------------------------

    private void queryReviews() throws SQLException, InterruptedException {
        Instant since = parseSinceWindow();
        List<String> statuses = parseStatuses();
        List<ReviewRecord> rows = reviewsRepo.query(since, statuses.isEmpty() ? null : statuses);
        SwingUtilities.invokeLater(() -> {
            tableModel.setColumnIdentifiers(new String[]{"Review ID", "Status", "Last Updated", "Repositories"});
            tableModel.setRowCount(0);
            for (ReviewRecord r : rows) {
                tableModel.addRow(new Object[]{
                    r.reviewId(),
                    r.status(),
                    r.lastUpdated() != null ? r.lastUpdated().toString() : "",
                    r.repositoriesJson()
                });
            }
            setStatus(rows.size() + " row(s) returned.");
        });
    }

    private Instant parseSinceWindow() {
        int idx = reviewsWindowCombo.getSelectedIndex();
        return switch (idx) {
            case 1 -> Instant.now().minus(24, ChronoUnit.HOURS);
            case 2 -> Instant.now().minus(7,  ChronoUnit.DAYS);
            case 3 -> Instant.now().minus(30, ChronoUnit.DAYS);
            default -> null;
        };
    }

    private List<String> parseStatuses() {
        String raw = reviewsStatusField.getText().trim();
        if (raw.isEmpty()) return List.of();
        return List.of(raw.split("\\s*,\\s*"));
    }

    // --- branches ---------------------------------------------------------------

    private void queryBranches() throws SQLException, InterruptedException {
        String prefix = blankToNull(branchPrefixField.getText());
        String owner  = blankToNull(branchOwnerField.getText());
        String repo   = blankToNull(branchRepoField.getText());
        List<BranchRecord> rows = branchRepo.query(prefix, owner, repo, 500, null);
        SwingUtilities.invokeLater(() -> {
            tableModel.setColumnIdentifiers(new String[]{"Owner", "Repository", "Branch Name"});
            tableModel.setRowCount(0);
            for (BranchRecord r : rows) {
                tableModel.addRow(new Object[]{ r.owner(), r.repository(), r.branchName() });
            }
            setStatus(rows.size() + " row(s) returned" + (rows.size() == 500 ? " (limit 500 reached)" : "") + ".");
        });
    }

    // --- repositories -----------------------------------------------------------

    private void queryRepositories() throws SQLException, InterruptedException {
        List<RepositoryRecord> rows = reposRepo.findAll();
        SwingUtilities.invokeLater(() -> {
            tableModel.setColumnIdentifiers(new String[]{"Owner", "Repository", "URL", "Kalynx Review Head"});
            tableModel.setRowCount(0);
            for (RepositoryRecord r : rows) {
                tableModel.addRow(new Object[]{
                    r.owner(), r.repository(), r.url(), r.kalynxReviewHead()
                });
            }
            setStatus(rows.size() + " row(s) returned.");
        });
    }

    // --- helpers ----------------------------------------------------------------

    private void setStatus(String msg) {
        statusLabel.setText(msg);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

}
