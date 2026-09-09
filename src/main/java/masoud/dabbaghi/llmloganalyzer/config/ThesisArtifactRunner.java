package masoud.dabbaghi.llmloganalyzer.config;

import lombok.RequiredArgsConstructor;
import masoud.dabbaghi.llmloganalyzer.thesis.ThesisArtifactService;
import masoud.dabbaghi.llmloganalyzer.visualization.EvaluationChartService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("thesis-artifacts")
@RequiredArgsConstructor
public class ThesisArtifactRunner implements CommandLineRunner {
    private final ThesisArtifactService artifactService;
    private final EvaluationChartService chartService;

    @Value("${charts.run-id:}")
    private String runId;

    @Override
    public void run(String... args) throws Exception {
        artifactService.exportCompletedRun(runId);
        chartService.generateAllCharts();
        artifactService.writeChecksumsAndVerify(runId);
    }
}
