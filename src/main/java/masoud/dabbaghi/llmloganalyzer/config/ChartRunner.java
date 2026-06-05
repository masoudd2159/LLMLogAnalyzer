package masoud.dabbaghi.llmloganalyzer.config;

import masoud.dabbaghi.llmloganalyzer.visualization.EvaluationChartService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("charts")
public class ChartRunner implements CommandLineRunner {

    private final EvaluationChartService chartService;

    public ChartRunner(EvaluationChartService chartService) {
        this.chartService = chartService;
    }

    @Override
    public void run(String... args) throws Exception {
        chartService.generateAllCharts();
    }
}