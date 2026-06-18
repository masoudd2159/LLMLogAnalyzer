package masoud.dabbaghi.llmloganalyzer.config;

import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.visualization.EvaluationChartService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("charts")
@Slf4j
public class ChartRunner implements CommandLineRunner {

    private final EvaluationChartService chartService;

    public ChartRunner(EvaluationChartService chartService) {
        this.chartService = chartService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting chart generation...");
        chartService.generateAllCharts();
        log.info("Chart generation finished.");
    }
}
