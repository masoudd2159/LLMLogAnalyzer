package masoud.dabbaghi.llmloganalyzer.config;

import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import masoud.dabbaghi.llmloganalyzer.service.BglParser;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** One-shot CLI execution path used by reproducible thesis runs. */
@Component
@Profile("experiment")
@Slf4j
public class ExperimentRunner implements CommandLineRunner {

    private final BglParser bglParser;

    public ExperimentRunner(BglParser bglParser) {
        this.bglParser = bglParser;
    }

    @Override
    public void run(String... args) throws Exception {
        BglExperimentRun run = bglParser.logParser();
        log.info("Experiment completed: runId={}, status={}", run.getRunId(), run.getStatus());
    }
}
