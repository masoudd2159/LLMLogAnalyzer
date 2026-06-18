package masoud.dabbaghi.llmloganalyzer.visualization;

import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import masoud.dabbaghi.llmloganalyzer.service.PromptGenerator;
import masoud.dabbaghi.llmloganalyzer.service.PromptSpec;
import org.bson.Document;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Paint;
import java.io.File;
import java.io.IOException;

@Service
public class EvaluationChartService {

    private static final int CHART_WIDTH = 1400;
    private static final int CHART_HEIGHT = 820;

    private static final String COLLECTION_NAME = "log_evaluations";

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

    private final MongoTemplate mongoTemplate;

    public EvaluationChartService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void generateAllCharts() throws IOException {
        PromptSpec finalPrompt = PromptGenerator.finalBglPrompt();

        MetricsSnapshot metrics = loadMetricsFromMongo(
                LogType.BGL,
                AiModel.OLLAMA,
                finalPrompt.experiment(),
                finalPrompt.version()
        );

        if (metrics.total() == 0) {
            System.out.println("No evaluation data found for chart generation.");
            return;
        }

        generateFinalMetricsChart(metrics);
        generateFinalConfusionMatrixChart(metrics);
        generateFinalInvalidRateChart(metrics);
        generateFinalResponseTimeChart(metrics);

        System.out.println("Charts generated successfully.");
    }

    private MetricsSnapshot loadMetricsFromMongo(
            LogType logType,
            AiModel aiModel,
            Object promptExperiment,
            String promptVersion
    ) {
        Criteria baseCriteria = Criteria.where("logType").is(logType)
                .and("aiModel").is(aiModel)
                .and("promptExperiment").is(promptExperiment)
                .and("promptVersion").is(promptVersion);

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(baseCriteria),
                Aggregation.group()
                        .count().as("total")

                        .sum(ConditionalOperators.when(
                                Criteria.where("aiResult").is(ClassificationResult.INVALID)
                        ).then(1).otherwise(0)).as("invalidTotal")

                        .sum(ConditionalOperators.when(
                                Criteria.where("realResult").is(ClassificationResult.ANOMALY)
                                        .and("aiResult").is(ClassificationResult.ANOMALY)
                        ).then(1).otherwise(0)).as("truePositive")

                        .sum(ConditionalOperators.when(
                                Criteria.where("realResult").is(ClassificationResult.NORMAL)
                                        .and("aiResult").is(ClassificationResult.NORMAL)
                        ).then(1).otherwise(0)).as("trueNegative")

                        .sum(ConditionalOperators.when(
                                Criteria.where("realResult").is(ClassificationResult.NORMAL)
                                        .and("aiResult").is(ClassificationResult.ANOMALY)
                        ).then(1).otherwise(0)).as("falsePositive")

                        .sum(ConditionalOperators.when(
                                Criteria.where("realResult").is(ClassificationResult.ANOMALY)
                                        .and("aiResult").is(ClassificationResult.NORMAL)
                        ).then(1).otherwise(0)).as("falseNegative")

                        .avg("responseTimeMs").as("averageResponseTimeMs")
        );

        AggregationResults<Document> results = mongoTemplate.aggregate(
                aggregation,
                COLLECTION_NAME,
                Document.class
        );

        Document result = results.getUniqueMappedResult();

        if (result == null) {
            return MetricsSnapshot.empty();
        }

        long total = getLong(result, "total");
        long invalidTotal = getLong(result, "invalidTotal");
        long validTotal = total - invalidTotal;

        long tp = getLong(result, "truePositive");
        long tn = getLong(result, "trueNegative");
        long fp = getLong(result, "falsePositive");
        long fn = getLong(result, "falseNegative");

        double accuracy = safeDivide(tp + tn, validTotal);
        double precision = safeDivide(tp, tp + fp);
        double recall = safeDivide(tp, tp + fn);
        double f1Score = safeDivide(2 * precision * recall, precision + recall);
        double invalidRate = safeDivide(invalidTotal, total);
        double averageResponseTimeMs = getDouble(result, "averageResponseTimeMs");

        return new MetricsSnapshot(
                total,
                validTotal,
                invalidTotal,
                tp,
                tn,
                fp,
                fn,
                accuracy,
                precision,
                recall,
                f1Score,
                invalidRate,
                averageResponseTimeMs
        );
    }

    private void generateFinalMetricsChart(MetricsSnapshot metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(metrics.accuracy(), "Score", "Accuracy");
        dataset.addValue(metrics.precision(), "Score", "Precision");
        dataset.addValue(metrics.recall(), "Score", "Recall");
        dataset.addValue(metrics.f1Score(), "Score", "F1");

        createBarChart(
                dataset,
                "Final Proposed Method - Main Evaluation Metrics",
                "Accuracy shows overall correctness. Precision shows reliability of anomaly alerts. Recall shows detected real anomalies. F1 balances Precision and Recall.",
                "Metric",
                "Score",
                "final_metrics.png",
                true
        );
    }

    private void generateFinalConfusionMatrixChart(MetricsSnapshot metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(metrics.truePositive(), "Count", "TP");
        dataset.addValue(metrics.trueNegative(), "Count", "TN");
        dataset.addValue(metrics.falsePositive(), "Count", "FP");
        dataset.addValue(metrics.falseNegative(), "Count", "FN");

        createBarChart(
                dataset,
                "Final Proposed Method - Confusion Matrix",
                "TP: anomaly correctly detected. TN: normal correctly detected. FP: normal falsely flagged as anomaly. FN: missed anomaly.",
                "Class",
                "Count",
                "final_confusion_matrix.png",
                false
        );
    }

    private void generateFinalInvalidRateChart(MetricsSnapshot metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(metrics.invalidRate(), "Invalid Rate", "Invalid Rate");

        createBarChart(
                dataset,
                "Final Proposed Method - Invalid Output Rate",
                "Invalid Rate indicates the percentage of model outputs that were not valid binary classifications.",
                "Metric",
                "Rate",
                "final_invalid_rate.png",
                true
        );
    }

    private void generateFinalResponseTimeChart(MetricsSnapshot metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(metrics.averageResponseTimeMs(), "Average Response Time", "Response Time");

        createBarChart(
                dataset,
                "Final Proposed Method - Average Response Time",
                "Average Response Time shows the mean inference time required to classify one log entry.",
                "Metric",
                "Milliseconds",
                "final_response_time.png",
                false
        );
    }

    private void createBarChart(
            DefaultCategoryDataset dataset,
            String title,
            String description,
            String categoryAxis,
            String valueAxis,
            String outputFile,
            boolean percentageScale
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
        stylePlot(chart.getCategoryPlot(), percentageScale);

        ChartUtils.saveChartAsPNG(
                new File(outputFile),
                chart,
                CHART_WIDTH,
                CHART_HEIGHT
        );
    }

    private void styleChart(JFreeChart chart, String description) {
        chart.setAntiAlias(true);
        chart.setTextAntiAlias(true);
        chart.setBackgroundPaint(PAGE_BACKGROUND);
        chart.setPadding(new RectangleInsets(18, 18, 18, 18));

        chart.getTitle().setFont(new Font("SansSerif", Font.BOLD, 24));
        chart.getTitle().setPaint(TITLE_COLOR);

        TextTitle subtitle = new TextTitle(
                description,
                new Font("SansSerif", Font.PLAIN, 13)
        );
        subtitle.setPaint(SUBTITLE_COLOR);
        subtitle.setPadding(new RectangleInsets(6, 4, 16, 4));

        chart.addSubtitle(subtitle);
    }

    private void stylePlot(CategoryPlot plot, boolean percentageScale) {
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

        if (percentageScale) {
            rangeAxis.setRange(0.0, 1.0);
        }

        PaletteBarRenderer renderer = new PaletteBarRenderer();
        renderer.setBarPainter(new StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setDrawBarOutline(false);
        renderer.setMaximumBarWidth(0.12);
        renderer.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator());
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelFont(new Font("SansSerif", Font.BOLD, 12));

        plot.setRenderer(renderer);
    }

    private static long getLong(Document document, String key) {
        Object value = document.get(key);

        if (value instanceof Number number) {
            return number.longValue();
        }

        return 0L;
    }

    private static double getDouble(Document document, String key) {
        Object value = document.get(key);

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return 0.0;
    }

    private static double safeDivide(double numerator, double denominator) {
        if (denominator == 0) {
            return 0.0;
        }

        return numerator / denominator;
    }

    private record MetricsSnapshot(
            long total,
            long validTotal,
            long invalidTotal,
            long truePositive,
            long trueNegative,
            long falsePositive,
            long falseNegative,
            double accuracy,
            double precision,
            double recall,
            double f1Score,
            double invalidRate,
            double averageResponseTimeMs
    ) {
        static MetricsSnapshot empty() {
            return new MetricsSnapshot(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0
            );
        }
    }

    private static final class PaletteBarRenderer extends BarRenderer {
        @Override
        public Paint getItemPaint(int row, int column) {
            return PALETTE[column % PALETTE.length];
        }
    }
}