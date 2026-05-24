package com.kalynx.centralindexer.gui.panel;

import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.metrics.MetricsCollector.TimeSeriesBuffer.Sample;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.themedcomponents.ThemedComboBox;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedTabbedPane;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.Collection;
import java.util.List;

/**
 * Dashboard panel: four metric-value cards at the top and a tabbed chart area below.
 * A single time-window selector drives both. The CPU chart shows one line per logical core.
 */
public final class StatisticsPanel extends ThemedPanel {

    private static final String[] WINDOW_LABELS = {
        "Last 1 second", "Last 60 seconds", "Last 60 minutes", "Last 24 hours"
    };
    private static final long[] WINDOW_MS = {
        1_000L, 60_000L, 3_600_000L, 86_400_000L
    };

    private final MetricsCollector metrics;
    private final ThemeManager     tm = ThemeManager.getInstance();

    private final MetricCard cpuCard;
    private final MetricCard memCard;
    private final MetricCard connCard;
    private final MetricCard apiCard;

    // CPU chart uses multiple lines (one per core); others use a single line.
    private final ScaledLineChart cpuChart;
    private final ScaledLineChart memChart;
    private final ScaledLineChart connChart;
    private final ScaledLineChart apiChart;

    private final ThemedComboBox<String> windowCombo;
    private Timer refreshTimer;

    public StatisticsPanel(MetricsCollector metrics) {
        super(new MigLayout("insets 12, gap 12",
                            "[grow][grow][grow][grow]",
                            "[][100::][grow]"));
        this.metrics = metrics;
        setOpaque(true);

        // --- time-window selector ------------------------------------------------
        ThemedPanel selectorRow = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        selectorRow.add(new ThemedLabel("Time window:"));
        windowCombo = new ThemedComboBox<>();
        for (String label : WINDOW_LABELS) windowCombo.addItem(label);
        selectorRow.add(windowCombo);
        add(selectorRow, "span 4, wrap");

        // --- metric cards --------------------------------------------------------
        cpuCard  = new MetricCard("CPU Usage",          "%",      new Color( 58, 150, 221));
        memCard  = new MetricCard("Memory Usage",       "MB",     new Color(150,  80, 221));
        connCard = new MetricCard("Active Connections", "",       new Color( 80, 200, 120));
        apiCard  = new MetricCard("Provider API Calls", "total",  new Color(221, 150,  58));

        add(cpuCard,  "grow");
        add(memCard,  "grow");
        add(connCard, "grow");
        add(apiCard,  "grow, wrap");

        // --- chart tabs ----------------------------------------------------------
        // CPU: fixed 0-100% scale, multiple lines (one per logical core).
        // Memory: fixed 0-maxHeap. Connections/API: dynamic.
        cpuChart  = new ScaledLineChart(new Color( 58, 150, 221), "%",  100.0);
        memChart  = new ScaledLineChart(new Color(150,  80, 221), "MB", MetricsCollector.memoryMaxMb());
        connChart = new ScaledLineChart(new Color( 80, 200, 120), "",   0);
        apiChart  = new ScaledLineChart(new Color(221, 150,  58), "",   0);

        ThemedTabbedPane chartTabs = new ThemedTabbedPane();
        chartTabs.addTab("CPU",         wrapChart(cpuChart));
        chartTabs.addTab("Memory",      wrapChart(memChart));
        chartTabs.addTab("Connections", wrapChart(connChart));
        chartTabs.addTab("API Calls",   wrapChart(apiChart));
        add(chartTabs, "span 4, growx, growy");
    }

    private static ThemedPanel wrapChart(ScaledLineChart chart) {
        ThemedPanel p = new ThemedPanel(new BorderLayout());
        p.add(chart, BorderLayout.CENTER);
        return p;
    }

    // --- lifecycle ---------------------------------------------------------------

    public void startRefresh() {
        refreshTimer = new Timer(1000, e -> refresh());
        refreshTimer.start();
    }

    public void stopRefresh() {
        if (refreshTimer != null) refreshTimer.stop();
    }

    private void refresh() {
        long windowMs = WINDOW_MS[Math.max(0, windowCombo.getSelectedIndex())];

        // Cards: total JVM CPU as % of all cores combined (0-100)
        cpuCard.update(String.format("%.1f",  metrics.getCpuSamples().average(windowMs)));
        memCard.update(String.format("%.0f",  metrics.getMemorySamples().average(windowMs)));
        connCard.update(String.format("%.1f", metrics.getConnectionSamples().average(windowMs)));
        apiCard.update(String.valueOf(        metrics.getApiCallSamples().count(windowMs)));

        // Per-core averages for the CPU card's core grid
        List<MetricsCollector.TimeSeriesBuffer> coreBufs = metrics.getPerCoreSamples();
        int n = coreBufs.size();
        double[] coreAvgs  = new double[n];
        Color[]  coreColors = new Color[n];
        for (int i = 0; i < n; i++) {
            coreAvgs[i]  = coreBufs.get(i).average(windowMs);
            coreColors[i] = (n == 1) ? new Color(58, 150, 221)
                                      : Color.getHSBColor((float) i / n, 0.75f, 1.0f);
        }
        cpuCard.updateCores(coreAvgs, coreColors);

        // CPU chart: one line per logical core (JVM per-core %)
        List<List<Sample>> coreWindows = metrics.getPerCoreSamples().stream()
                .map(buf -> buf.getWindow(windowMs))
                .toList();
        cpuChart.setDataSets(coreWindows);

        memChart.setData(metrics.getMemorySamples().getWindow(windowMs));
        connChart.setData(metrics.getConnectionSamples().getWindow(windowMs));
        apiChart.setData(metrics.getApiCallSamples().getWindow(windowMs));
    }

    // =====================================================================
    // MetricCard — themed panel showing a large value + unit label
    // =====================================================================

    private final class MetricCard extends ThemedPanel {

        private final Color       accentColor;
        private final ThemedLabel titleLabel;
        private final JLabel      valueLabel;

        private JLabel[] coreLabels;

        MetricCard(String title, String unit, Color accent) {
            super(new MigLayout("insets 12, gap 6, flowy", "[grow]", "[][][]"));
            this.accentColor = accent;
            setOpaque(true);

            titleLabel = new ThemedLabel(title);
            titleLabel.setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(11)));

            valueLabel = new JLabel("—");
            valueLabel.setFont(tm.getBaseFont().deriveFont(Font.BOLD, (float) tm.scale(28)));
            valueLabel.setForeground(accent);

            ThemedLabel unitLabel = new ThemedLabel(unit);
            unitLabel.setFont(tm.getBaseFont().deriveFont(Font.PLAIN, tm.scale(11)));

            add(titleLabel, "growx");
            add(valueLabel, "growx");
            add(unitLabel,  "growx");

            tm.addThemeChangeListener(this::applyCardBorder);
            applyCardBorder();
        }

        void update(String value) { valueLabel.setText(value); }

        void updateCores(double[] pcts, Color[] colors) {
            if (coreLabels == null) {
                coreLabels = new JLabel[pcts.length];
                int cols = Math.max(2, (int) Math.ceil(Math.sqrt(pcts.length)));
                StringBuilder colSpec = new StringBuilder();
                for (int i = 0; i < cols; i++) colSpec.append("[grow,fill]");
                JPanel grid = new JPanel(new MigLayout(
                    "insets 0, gap 4 2, wrap " + cols, colSpec.toString()));
                grid.setOpaque(false);
                for (int i = 0; i < pcts.length; i++) {
                    JLabel lbl = new JLabel();
                    lbl.setFont(tm.getBaseFont().deriveFont(Font.PLAIN, tm.scale(9)));
                    lbl.setForeground(i < colors.length ? colors[i] : accentColor);
                    coreLabels[i] = lbl;
                    grid.add(lbl, "growx");
                }
                add(grid, "growx");
                revalidate();
            }
            for (int i = 0; i < pcts.length && i < coreLabels.length; i++) {
                coreLabels[i].setText(String.format("CPU %d: %04.1f%%", i, pcts[i]));
            }
        }

        private void applyCardBorder() {
            Theme t = tm.getCurrentTheme();
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accentColor.darker(), 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            ));
            setBackground(t.getBackgroundColor());
            if (titleLabel != null) titleLabel.setForeground(t.getSecondaryTextColor());
        }
    }

    // =====================================================================
    // ScaledLineChart — custom chart with Y-axis scale and multiple series
    // =====================================================================

    private static final class ScaledLineChart extends JPanel {

        private static final int LEFT_PAD  = 60;
        private static final int RIGHT_PAD = 12;
        private static final int TOP_PAD   = 10;
        private static final int BOT_PAD   = 10;
        private static final int GRID_DIVS = 4;

        private final Color  lineColor; // used when there is only one dataset
        private final String unit;
        private final double fixedMax;  // <= 0 → dynamic

        private List<List<Sample>> datasets  = List.of();
        private double             dynamicMax = 10;

        ScaledLineChart(Color lineColor, String unit, double fixedMax) {
            this.lineColor = lineColor;
            this.unit      = unit;
            this.fixedMax  = fixedMax;
            setOpaque(true);
            ThemeManager.getInstance().addThemeChangeListener(this::repaint);
        }

        /** Convenience for a single data series. */
        void setData(List<Sample> data) {
            setDataSets(List.of(data));
        }

        /** Sets N data series; the CPU chart uses one per logical core. */
        void setDataSets(List<List<Sample>> newDatasets) {
            this.datasets = newDatasets;
            if (fixedMax <= 0 && !newDatasets.isEmpty()) {
                double max = newDatasets.stream()
                        .flatMap(Collection::stream)
                        .mapToDouble(Sample::value).max().orElse(0);
                dynamicMax = Math.max(dynamicMax, Math.max(max * 1.2, 10));
            }
            repaint();
        }

        /** Returns a hue-spaced color for dataset index {@code i} out of {@code n}. */
        private Color datasetColor(int i, int n) {
            if (n == 1) return lineColor;
            return Color.getHSBColor((float) i / n, 0.75f, 1.0f);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Theme theme = ThemeManager.getInstance().getCurrentTheme();
            setBackground(theme.getBackgroundColor());

            int w = getWidth();
            int h = getHeight();
            if (w < LEFT_PAD + RIGHT_PAD + 20 || h < TOP_PAD + BOT_PAD + 20) return;

            int chartX = LEFT_PAD;
            int chartY = TOP_PAD;
            int chartW = w - LEFT_PAD - RIGHT_PAD;
            int chartH = h - TOP_PAD  - BOT_PAD;

            double yMax = fixedMax > 0 ? fixedMax : dynamicMax;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Font        labelFont = ThemeManager.getInstance().getBaseFont().deriveFont(Font.PLAIN, 10f);
            g2.setFont(labelFont);
            FontMetrics fm        = g2.getFontMetrics();

            Color gridColor  = new Color(theme.getForegroundColor().getRed(),
                                         theme.getForegroundColor().getGreen(),
                                         theme.getForegroundColor().getBlue(), 35);
            Color labelColor = theme.getSecondaryTextColor();

            // Y-axis grid lines + labels
            for (int i = 0; i <= GRID_DIVS; i++) {
                double frac  = (double) i / GRID_DIVS;
                int    lineY = chartY + chartH - (int) (frac * chartH);

                g2.setColor(gridColor);
                g2.setStroke(new BasicStroke(0.5f));
                g2.drawLine(chartX, lineY, chartX + chartW, lineY);

                String lbl = formatLabel(frac * yMax);
                int    lw  = fm.stringWidth(lbl);
                g2.setColor(labelColor);
                g2.drawString(lbl, chartX - lw - 4, lineY + fm.getAscent() / 2 - 1);
            }

            // Chart border
            g2.setColor(gridColor);
            g2.setStroke(new BasicStroke(0.5f));
            g2.drawRect(chartX, chartY, chartW, chartH);

            g2.clipRect(chartX, chartY, chartW, chartH);

            boolean single = datasets.size() == 1;

            for (int di = 0; di < datasets.size(); di++) {
                List<Sample> data  = datasets.get(di);
                Color        color = datasetColor(di, datasets.size());

                if (data.size() >= 2) {
                    long tMin = data.getFirst().timestampMs();
                    long tMax = data.getLast().timestampMs();
                    if (tMax == tMin) tMax = tMin + 1;

                    Path2D path = new Path2D.Float();
                    boolean first = true;
                    for (Sample s : data) {
                        float x = chartX + (float)(s.timestampMs() - tMin) / (tMax - tMin) * chartW;
                        float y = chartY + chartH - (float)(Math.min(s.value(), yMax) / yMax) * chartH;
                        if (first) { path.moveTo(x, y); first = false; }
                        else        path.lineTo(x, y);
                    }

                    // Fill only for single-series charts
                    if (single) {
                        Path2D fill = new Path2D.Float(path);
                        Sample last = data.getLast();
                        float  lx   = chartX + (float)(last.timestampMs() - tMin) / (tMax - tMin) * chartW;
                        fill.lineTo(lx, chartY + chartH);
                        fill.lineTo(chartX, chartY + chartH);
                        fill.closePath();
                        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
                        g2.fill(fill);
                    }

                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(path);

                } else if (data.size() == 1) {
                    float y = chartY + chartH - (float)(Math.min(data.getFirst().value(), yMax) / yMax) * chartH;
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawLine(chartX, (int) y, chartX + chartW, (int) y);
                }
            }

            if (datasets.isEmpty() || datasets.stream().allMatch(List::isEmpty)) {
                g2.setColor(labelColor);
                g2.setFont(labelFont.deriveFont(Font.ITALIC));
                String msg = "No data";
                int    mw  = g2.getFontMetrics().stringWidth(msg);
                g2.drawString(msg, chartX + (chartW - mw) / 2, chartY + chartH / 2);
            }

            g2.dispose();
        }

        private String formatLabel(double value) {
            if ("%".equals(unit))  return String.format("%.0f%%", value);
            if ("MB".equals(unit)) {
                if (value >= 1024) return String.format("%.1fG", value / 1024);
                return String.format("%.0fM", value);
            }
            if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000);
            if (value >= 1_000)     return String.format("%.0fK", value / 1_000);
            return String.format("%.0f", value);
        }
    }
}
