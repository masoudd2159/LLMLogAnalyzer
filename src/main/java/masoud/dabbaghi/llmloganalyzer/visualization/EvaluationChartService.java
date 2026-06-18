package masoud.dabbaghi.llmloganalyzer.visualization;

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
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;

/**
 * Generates thesis-ready charts from aggregated MongoDB metrics.
 *
 * The service never loads the full log_evaluations collection into memory.
 */
@Service
public class EvaluationChartService {

    private static final int CHART_WIDTH = 1400;
    private static final int CHART_HEIGHT = 820;

    private static final Color PAGE_BACKGROUND = Color.decode("#F8FAFC");
    private static final Color PLOT_BACKGROUND = Color.WHITE;
    private static final Color GRID_COLOR = Color.decode("#E5E7EB");
    private static final Color TITLE_COLOR = Color.decode("#111827");
    private static final Color SUBTITLE_COLOR = Color.decode("#4B5563");
    private static final Color AXIS_COLOR = Color.decode("#374151");

    private static final Color[] PALETTE = new Color[]{
            Color.decode("#2563EB"),
            Color.decode("#16A34A"),
            Color.decode("#F97316"),
            Color.decode("#DC2626"),
            Color.decode("#7C3AED"),
            Color.decode("#0891B2")
    };

    private final EvaluationMetricsService metricsService;

    public EvaluationChartService(EvaluationMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void generateAllCharts() throws IOException {
        PromptSpec finalPrompt = PromptGenerator.finalBglPrompt();

        EvaluationMetrics metrics = metricsService.calculate(
                LogType.BGL,
                AiModel.OLLAMA,
                finalPrompt.experiment(),
                finalPrompt.version()
        );

        generateFinalMetricsChart(metrics);
        generateFinalConfusionMatrixChart(metrics);
        generateFinalInvalidRateChart(metrics);
        generateFinalResponseTimeChart(metrics);
        generateDecisionSourceChart(metrics);
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
                "Metrics are calculated by MongoDB-side aggregation without loading all records into JVM memory.",
                "Metric",
                "Score",
                "final_metrics.png",
                AxisMode.RATE,
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
                "TP: anomaly detected. TN: normal detected. FP: normal falsely flagged. FN: missed anomaly.",
                "Class",
                "Count",
                "final_confusion_matrix.png",
                AxisMode.COUNT,
                NumberFormat.getIntegerInstance()
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
                AxisMode.RATE,
                new DecimalFormat("0.000000")
        );
    }

    private void generateFinalResponseTimeChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(metrics.averageResponseTimeMs(), "Milliseconds", "Line Avg");
        dataset.addValue(metrics.llmAverageResponseTimeMs(), "Milliseconds", "LLM Avg");

        createBarChart(
                dataset,
                "Final Proposed Method - Average Response Time",
                "Line Avg includes cache/guard zero-time decisions. LLM Avg includes only records that actually called the model.",
                "Metric",
                "Milliseconds",
                "final_response_time.png",
                AxisMode.AUTO,
                new DecimalFormat("0.00")
        );
    }

    private void generateDecisionSourceChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(metrics.llmDecisionTotal(), "Count", "LLM");
        dataset.addValue(metrics.templateCacheDecisionTotal(), "Count", "Cache");
        dataset.addValue(metrics.templateGuardDecisionTotal(), "Count", "Guard");
        dataset.addValue(metrics.cacheHitTotal(), "Count", "Cache Hits");

        createBarChart(
                dataset,
                "Final Proposed Method - Decision Sources",
                "Shows how many line-level predictions came from the LLM, template cache, or deterministic guard.",
                "Source",
                "Count",
                "final_decision_sources.png",
                AxisMode.COUNT,
                NumberFormat.getIntegerInstance()
        );
    }

    private void createBarChart(
            DefaultCategoryDataset dataset,
            String title,
            String description,
            String categoryAxis,
            String valueAxis,
            String outputFile,
            AxisMode axisMode,
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
        stylePlot(chart.getCategoryPlot(), axisMode, labelFormat);

        ChartUtils.saveChartAsPNG(new File(outputFile), chart, CHART_WIDTH, CHART_HEIGHT);
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

    private void stylePlot(CategoryPlot plot, AxisMode axisMode, NumberFormat labelFormat) {
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

        if (axisMode == AxisMode.RATE) {
            rangeAxis.setRange(0.0, 1.0);
        } else if (axisMode == AxisMode.COUNT) {
            rangeAxis.setNumberFormatOverride(NumberFormat.getIntegerInstance());
        }

        PaletteBarRenderer renderer = new PaletteBarRenderer();
        renderer.setBarPainter(new StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setDrawBarOutline(false);
        renderer.setMaximumBarWidth(0.14);
        renderer.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}", labelFormat));
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelFont(new Font("SansSerif", Font.BOLD, 12));

        plot.setRenderer(renderer);
    }

    private enum AxisMode {
        RATE,
        COUNT,
        AUTO
    }

    private static final class PaletteBarRenderer extends BarRenderer {
        @Override
        public Paint getItemPaint(int row, int column) {
            return PALETTE[column % PALETTE.length];
        }
    }
}
