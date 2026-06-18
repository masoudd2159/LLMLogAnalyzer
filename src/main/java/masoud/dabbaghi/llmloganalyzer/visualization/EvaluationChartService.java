package masoud.dabbaghi.llmloganalyzer.visualization;

import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.evaluation.EvaluationMetrics;
import masoud.dabbaghi.llmloganalyzer.evaluation.EvaluationMetricsService;
import masoud.dabbaghi.llmloganalyzer.service.PromptGenerator;
import masoud.dabbaghi.llmloganalyzer.service.PromptSpec;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.ItemLabelAnchor;
import org.jfree.chart.labels.ItemLabelPosition;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Generates thesis-ready charts without loading all LogEvaluation rows into memory.
 */
@Service
@Slf4j
public class EvaluationChartService {

    private static final int CHART_WIDTH = 1400;
    private static final int CHART_HEIGHT = 820;

    private static final Color PAGE_BACKGROUND = Color.decode("#F8FAFC");
    private static final Color PLOT_BACKGROUND = Color.WHITE;
    private static final Color GRID_COLOR = Color.decode("#E5E7EB");
    private static final Color TITLE_COLOR = Color.decode("#111827");
    private static final Color SUBTITLE_COLOR = Color.decode("#4B5563");
    private static final Color AXIS_COLOR = Color.decode("#374151");
    private static final Color LABEL_COLOR = Color.decode("#111827");

    private static final Color[] PALETTE = new Color[]{
            Color.decode("#2563EB"),
            Color.decode("#16A34A"),
            Color.decode("#F97316"),
            Color.decode("#DC2626"),
            Color.decode("#7C3AED"),
            Color.decode("#0891B2")
    };

    private final EvaluationMetricsService metricsService;

    @Value("${charts.data.scope:all}")
    private String chartDataScope;

    @Value("${charts.fail-on-empty:true}")
    private boolean failOnEmpty;

    @Value("${charts.output-dir:.}")
    private String outputDir;

    public EvaluationChartService(EvaluationMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void generateAllCharts() throws IOException {
        PromptSpec finalPrompt = PromptGenerator.finalBglPrompt();

        EvaluationMetrics metrics = metricsService.calculateForCharts(
                LogType.BGL,
                AiModel.OLLAMA,
                finalPrompt.experiment(),
                finalPrompt.version(),
                chartDataScope
        );

        if (metrics.total() == 0) {
            String message = "No evaluation data found for charts. "
                    + "Check MongoDB collection 'log_evaluations', logType=BGL, aiModel=OLLAMA, "
                    + "and charts.data.scope=" + chartDataScope + ". "
                    + "No zero-valued charts were generated.";

            if (failOnEmpty) {
                throw new IllegalStateException(message);
            }

            log.warn(message);
            return;
        }

        log.info(
                "Generating charts for selection='{}', total={}, valid={}, invalid={}, accuracy={}, precision={}, recall={}, f1={}, templateCacheSize={}",
                metrics.selectionDescription(),
                metrics.total(),
                metrics.validTotal(),
                metrics.invalidTotal(),
                metrics.accuracy(),
                metrics.precision(),
                metrics.recall(),
                metrics.f1Score(),
                metrics.templateCacheSize()
        );

        generateFinalMetricsChart(metrics);
        generateFinalConfusionMatrixChart(metrics);
        generateFinalInvalidRateChart(metrics);
        generateFinalResponseTimeChart(metrics);
        generateFinalDecisionSourceChart(metrics);
        generateFinalTemplateCacheSizeChart(metrics);
    }

    private void generateFinalMetricsChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        dataset.addValue(metrics.accuracy(), "Score", "Accuracy");
        dataset.addValue(metrics.precision(), "Score", "Precision");
        dataset.addValue(metrics.recall(), "Score", "Recall");
        dataset.addValue(metrics.f1Score(), "Score", "F1");

        createBarChart(
                dataset,
                "Final Proposed Method - Main Evaluation Metrics",
                "Records: " + formatLong(metrics.total()) + " | " + metrics.selectionDescription(),
                "Metric",
                "Score",
                "final_metrics.png",
                true,
                new DecimalFormat("0.0000")
        );
    }

    private void generateFinalConfusionMatrixChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        dataset.addValue(metrics.truePositive(), "Count", "TP");
        dataset.addValue(metrics.trueNegative(), "Count", "TN");
        dataset.addValue(metrics.falsePositive(), "Count", "FP");
        dataset.addValue(metrics.falseNegative(), "Count", "FN");

        createBarChart(
                dataset,
                "Final Proposed Method - Confusion Matrix",
                "TP: anomaly detected. TN: normal detected. FP: false alarm. FN: missed anomaly.",
                "Class",
                "Count",
                "final_confusion_matrix.png",
                false,
                NumberFormat.getIntegerInstance(Locale.US)
        );
    }

    private void generateFinalInvalidRateChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        dataset.addValue(metrics.invalidRate(), "Invalid Rate", "Invalid Rate");

        createBarChart(
                dataset,
                "Final Proposed Method - Invalid Output Rate",
                "Invalid Rate is the ratio of invalid model outputs to all evaluated log entries.",
                "Metric",
                "Rate",
                "final_invalid_rate.png",
                true,
                new DecimalFormat("0.000000")
        );
    }

    private void generateFinalResponseTimeChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        dataset.addValue(metrics.averageResponseTimeMs(), "Milliseconds", "Line Avg");
        dataset.addValue(metrics.averageLlmResponseTimeMs(), "Milliseconds", "LLM Avg");

        createBarChart(
                dataset,
                "Final Proposed Method - Average Response Time",
                "Line Avg includes cache/guard zero-time decisions. LLM Avg includes only records that called the model.",
                "Metric",
                "Milliseconds",
                "final_response_time.png",
                false,
                new DecimalFormat("0.00")
        );
    }

    private void generateFinalDecisionSourceChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        dataset.addValue(metrics.llmDecisionCount(), "Count", "LLM");
        dataset.addValue(metrics.templateCacheDecisionCount(), "Count", "Template Cache");
        dataset.addValue(metrics.templateGuardDecisionCount(), "Count", "Template Guard");

        double cacheHitRate = safeDivide(metrics.cacheHitCount(), metrics.total());
        double llmRate = safeDivide(metrics.llmDecisionCount(), metrics.total());
        double guardRate = safeDivide(metrics.templateGuardDecisionCount(), metrics.total());

        createBarChart(
                dataset,
                "Final Proposed Method - Decision Sources",
                "Line-level decisions. Cache Size is reported separately: " + formatLong(metrics.templateCacheSize())
                        + " unique cacheable templates. Cache Hit Rate: " + formatPercent(cacheHitRate)
                        + " | LLM Rate: " + formatPercent(llmRate)
                        + " | Guard Rate: " + formatPercent(guardRate),
                "Source",
                "Count",
                "final_decision_sources.png",
                false,
                NumberFormat.getIntegerInstance(Locale.US)
        );
    }

    private void generateFinalTemplateCacheSizeChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        dataset.addValue(metrics.templateCacheSize(), "Count", "Template Cache Size");

        createBarChart(
                dataset,
                "Final Proposed Method - Template Cache Size",
                "Template Cache Size is the number of unique cacheable template keys in the selected evaluation scope.",
                "Metric",
                "Unique Templates",
                "final_template_cache_size.png",
                false,
                NumberFormat.getIntegerInstance(Locale.US)
        );
    }

    private void createBarChart(
            DefaultCategoryDataset dataset,
            String title,
            String description,
            String categoryAxis,
            String valueAxis,
            String outputFile,
            boolean ratioScale,
            NumberFormat labelFormat
    ) throws IOException {
        JFreeChart chart = ChartFactory.createBarChart(
                title,
                categoryAxis,
                valueAxis,
                dataset,
                PlotOrientation.VERTICAL,
                false,
                true,
                false
        );

        styleChart(chart, description);
        stylePlot(chart.getCategoryPlot(), dataset, ratioScale, labelFormat);

        File target = resolveOutputFile(outputFile);
        ChartUtils.saveChartAsPNG(target, chart, CHART_WIDTH, CHART_HEIGHT);
        log.info("Saved chart: {}", target.getAbsolutePath());
    }

    private void styleChart(JFreeChart chart, String description) {
        chart.setAntiAlias(true);
        chart.setTextAntiAlias(true);
        chart.setBackgroundPaint(PAGE_BACKGROUND);
        chart.setPadding(new RectangleInsets(18, 18, 18, 18));

        chart.getTitle().setFont(new Font("SansSerif", Font.BOLD, 24));
        chart.getTitle().setPaint(TITLE_COLOR);

        TextTitle subtitle = new TextTitle(description, new Font("SansSerif", Font.PLAIN, 13));
        subtitle.setPaint(SUBTITLE_COLOR);
        subtitle.setPadding(new RectangleInsets(6, 4, 16, 4));
        chart.addSubtitle(subtitle);
    }

    private void stylePlot(
            CategoryPlot plot,
            DefaultCategoryDataset dataset,
            boolean ratioScale,
            NumberFormat labelFormat
    ) {
        plot.setBackgroundPaint(PLOT_BACKGROUND);
        plot.setOutlineVisible(false);
        plot.setRangeGridlinePaint(GRID_COLOR);
        plot.setDomainGridlinesVisible(false);
        plot.setAxisOffset(new RectangleInsets(8, 8, 8, 8));

        CategoryAxis domainAxis = plot.getDomainAxis();
        domainAxis.setLabelFont(new Font("SansSerif", Font.BOLD, 14));
        domainAxis.setLabelPaint(AXIS_COLOR);
        domainAxis.setTickLabelFont(new Font("SansSerif", Font.PLAIN, 13));
        domainAxis.setTickLabelPaint(AXIS_COLOR);
        domainAxis.setCategoryMargin(0.25);
        domainAxis.setLowerMargin(0.04);
        domainAxis.setUpperMargin(0.04);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setLabelFont(new Font("SansSerif", Font.BOLD, 14));
        rangeAxis.setLabelPaint(AXIS_COLOR);
        rangeAxis.setTickLabelFont(new Font("SansSerif", Font.PLAIN, 12));
        rangeAxis.setTickLabelPaint(AXIS_COLOR);
        rangeAxis.setAutoRangeIncludesZero(true);

        double maxValue = findMaxValue(dataset);
        if (ratioScale) {
            double upperBound = maxValue <= 0 ? 1.0 : Math.max(1.05, maxValue * 1.08);
            rangeAxis.setRange(0.0, upperBound);
        } else if (maxValue <= 0) {
            rangeAxis.setRange(0.0, 1.0);
        } else {
            rangeAxis.setRange(0.0, maxValue * 1.15);
        }

        PaletteBarRenderer renderer = new PaletteBarRenderer();
        renderer.setBarPainter(new StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setDrawBarOutline(false);
        renderer.setMaximumBarWidth(0.12);
        renderer.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}", labelFormat));
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelFont(new Font("SansSerif", Font.BOLD, 12));
        renderer.setDefaultItemLabelPaint(LABEL_COLOR);
        renderer.setDefaultPositiveItemLabelPosition(
                new ItemLabelPosition(ItemLabelAnchor.OUTSIDE12, TextAnchor.BOTTOM_CENTER)
        );

        plot.setRenderer(renderer);
    }

    private double findMaxValue(DefaultCategoryDataset dataset) {
        double max = 0;
        for (int row = 0; row < dataset.getRowCount(); row++) {
            for (int column = 0; column < dataset.getColumnCount(); column++) {
                Number value = dataset.getValue(row, column);
                if (value != null) {
                    max = Math.max(max, value.doubleValue());
                }
            }
        }
        return max;
    }

    private File resolveOutputFile(String outputFile) {
        File dir = new File(outputDir == null || outputDir.isBlank() ? "." : outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Could not create chart output directory: {}", dir.getAbsolutePath());
        }
        return new File(dir, outputFile);
    }

    private String formatLong(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    private String formatPercent(double value) {
        return new DecimalFormat("0.00%").format(value);
    }

    private double safeDivide(double numerator, double denominator) {
        if (denominator == 0) {
            return 0;
        }
        return numerator / denominator;
    }

    private static final class PaletteBarRenderer extends BarRenderer {
        @Override
        public Paint getItemPaint(int row, int column) {
            return PALETTE[column % PALETTE.length];
        }
    }
}
