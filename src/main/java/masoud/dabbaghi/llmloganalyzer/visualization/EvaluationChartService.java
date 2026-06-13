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
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.File;
import java.io.IOException;

/**
 * Generates thesis-ready charts for the final proposed method.
 * <p>
 * Positive class = anomaly / alert.
 * Negative class = normal / non-alert.
 * <p>
 * TP: anomaly correctly detected as anomaly.
 * TN: normal correctly detected as normal.
 * FP: normal incorrectly detected as anomaly.
 * FN: anomaly incorrectly detected as normal.
 */
@Service
public class EvaluationChartService {

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
                "Accuracy shows overall correctness. Precision shows reliability of anomaly alerts. Recall shows detected real anomalies. F1 balances Precision and Recall.",
                "Metric",
                "Score",
                "final_metrics.png"
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
                "TP: anomaly correctly detected. TN: normal correctly detected. FP: normal falsely flagged as anomaly. FN: missed anomaly.",
                "Class",
                "Count",
                "final_confusion_matrix.png"
        );
    }

    private void generateFinalInvalidRateChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(metrics.invalidRate(), "Invalid Rate", "Invalid Rate");

        createBarChart(
                dataset,
                "Final Proposed Method - Invalid Output Rate",
                "Invalid Rate indicates the percentage of model outputs that were not valid JSON labels.",
                "Metric",
                "Rate",
                "final_invalid_rate.png"
        );
    }

    private void generateFinalResponseTimeChart(EvaluationMetrics metrics) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(metrics.averageResponseTimeMs(), "Average Response Time", "Response Time");

        createBarChart(
                dataset,
                "Final Proposed Method - Average Response Time",
                "Average Response Time shows the mean inference time required to classify one log entry.",
                "Metric",
                "Milliseconds",
                "final_response_time.png"
        );
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
        renderer.setSeriesPaint(0, Color.decode("#1f77b4"));

        ChartUtils.saveChartAsPNG(new File(outputFile), chart, 1200, 700);
    }
}