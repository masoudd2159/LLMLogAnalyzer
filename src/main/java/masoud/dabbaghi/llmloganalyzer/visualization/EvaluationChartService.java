package masoud.dabbaghi.llmloganalyzer.visualization;

import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.evaluation.EvaluationMetrics;
import masoud.dabbaghi.llmloganalyzer.evaluation.EvaluationMetricsService;
import masoud.dabbaghi.llmloganalyzer.service.PromptGenerator;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Generates thesis-ready evaluation charts for BGL log anomaly detection.
 * <p>
 * Positive class = anomaly.
 * Negative class = normal.
 * <p>
 * TP: anomaly correctly detected as anomaly.
 * TN: normal correctly detected as normal.
 * FP: normal incorrectly detected as anomaly.
 * FN: anomaly incorrectly detected as normal.
 * <p>
 * Accuracy: overall correctness.
 * Precision: reliability of anomaly alerts.
 * Recall: ability to detect real anomalies.
 * F1-score: balance between Precision and Recall.
 * Invalid Rate: percentage of invalid model outputs.
 * Average Response Time: mean inference time per log entry.
 */
@Service
public class EvaluationChartService {

    private final EvaluationMetricsService metricsService;

    public EvaluationChartService(EvaluationMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void generateAllCharts() throws IOException {
        List<EvaluationMetrics> allMetrics = PromptGenerator.bglPromptExperiments().stream()
                .map(spec -> metricsService.calculate(
                        LogType.BGL,
                        AiModel.OLLAMA,
                        spec.experiment(),
                        spec.version()
                ))
                .toList();

        generateMetricComparisonChart(
                allMetrics,
                "Accuracy, Precision, Recall, F1",
                "metrics_comparison.png"
        );

        generateBarChart(
                allMetrics,
                "Invalid Rate per Prompt",
                "Invalid Rate indicates the percentage of model outputs that were not valid JSON labels.",
                "invalid_rate.png",
                EvaluationMetrics::invalidRate
        );

        generateBarChart(
                allMetrics,
                "Average Response Time (ms) per Prompt",
                "Average Response Time shows the mean inference time required to classify one log entry.",
                "response_time.png",
                EvaluationMetrics::averageResponseTimeMs
        );

        generateConfusionMatrixCharts(allMetrics);
    }

    private void generateMetricComparisonChart(
            List<EvaluationMetrics> metrics,
            String title,
            String outputFile
    ) throws IOException {

        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        for (EvaluationMetrics m : metrics) {
            String prompt = m.promptExperiment().name();
            dataset.addValue(m.accuracy(), "Accuracy", prompt);
            dataset.addValue(m.precision(), "Precision", prompt);
            dataset.addValue(m.recall(), "Recall", prompt);
            dataset.addValue(m.f1Score(), "F1", prompt);
        }

        createBarChart(
                dataset,
                title,
                "Accuracy shows overall correctness. Precision shows reliability of anomaly alerts. Recall shows detected real anomalies. F1 balances Precision and Recall.",
                "Prompt",
                "Score",
                outputFile
        );
    }

    private void generateBarChart(
            List<EvaluationMetrics> metrics,
            String title,
            String description,
            String outputFile,
            MetricValueExtractor extractor
    ) throws IOException {

        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        for (EvaluationMetrics m : metrics) {
            String prompt = m.promptExperiment().name();
            dataset.addValue(extractor.getValue(m), "Value", prompt);
        }

        createBarChart(
                dataset,
                title,
                description,
                "Prompt",
                "Value",
                outputFile
        );
    }

    private void generateConfusionMatrixCharts(List<EvaluationMetrics> metrics) throws IOException {
        for (EvaluationMetrics m : metrics) {
            DefaultCategoryDataset dataset = new DefaultCategoryDataset();

            dataset.addValue(m.truePositive(), "TP", "TP");
            dataset.addValue(m.trueNegative(), "TN", "TN");
            dataset.addValue(m.falsePositive(), "FP", "FP");
            dataset.addValue(m.falseNegative(), "FN", "FN");

            createBarChart(
                    dataset,
                    "Confusion Matrix: " + m.promptExperiment().name(),
                    "TP: anomaly correctly detected. TN: normal correctly detected. FP: normal falsely flagged as anomaly. FN: missed anomaly.",
                    "Class",
                    "Count",
                    "confusion_" + m.promptExperiment().name() + ".png"
            );
        }
    }

    private void createBarChart(
            DefaultCategoryDataset dataset,
            String title,
            String description,
            String categoryAxis,
            String valueAxis,
            String outputFile
    ) throws IOException {

        JFreeChart chart = ChartFactory.createBarChart(
                title,
                categoryAxis,
                valueAxis,
                dataset
        );

        chart.addSubtitle(new TextTitle(
                description,
                new Font("SansSerif", Font.PLAIN, 12)
        ));

        CategoryPlot plot = chart.getCategoryPlot();
        BarRenderer renderer = (BarRenderer) plot.getRenderer();
        renderer.setDrawBarOutline(false);

        renderer.setSeriesPaint(0, Color.decode("#1f77b4")); // Blue
        if (dataset.getRowCount() > 1) {
            renderer.setSeriesPaint(1, Color.decode("#ff7f0e")); // Orange
        }
        if (dataset.getRowCount() > 2) {
            renderer.setSeriesPaint(2, Color.decode("#2ca02c")); // Green
        }
        if (dataset.getRowCount() > 3) {
            renderer.setSeriesPaint(3, Color.decode("#d62728")); // Red
        }

        ChartUtils.saveChartAsPNG(new File(outputFile), chart, 1200, 700);
    }

    @FunctionalInterface
    interface MetricValueExtractor {
        double getValue(EvaluationMetrics m);
    }
}