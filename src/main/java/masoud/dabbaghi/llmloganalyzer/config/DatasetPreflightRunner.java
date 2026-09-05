package masoud.dabbaghi.llmloganalyzer.config;

import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.service.BglDatasetPreflightReport;
import masoud.dabbaghi.llmloganalyzer.service.BglDatasetPreflightService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("preprocess")
@Slf4j
public class DatasetPreflightRunner implements CommandLineRunner {

    private final BglDatasetPreflightService service;

    public DatasetPreflightRunner(BglDatasetPreflightService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) throws Exception {
        BglDatasetPreflightReport report = service.inspectAndWriteReport();
        log.info("BGL dataset preflight completed: {}", report);
    }
}
