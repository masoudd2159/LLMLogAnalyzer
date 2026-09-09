package masoud.dabbaghi.llmloganalyzer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.service.BglEvaluationPlan;
import masoud.dabbaghi.llmloganalyzer.service.BglEvaluationScopeResolver;
import masoud.dabbaghi.llmloganalyzer.service.BglDatasetPreflightReport;
import masoud.dabbaghi.llmloganalyzer.service.BglDatasetPreflightService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Profile("preprocess")
@Slf4j
public class DatasetPreflightRunner implements CommandLineRunner {

    private final BglDatasetPreflightService service;
    private final ObjectMapper objectMapper;

    @Value("${bgl.limit-explicit:false}")
    private boolean callerProvidedLimit;

    @Value("${bgl.max-records:-1}")
    private long requestedLimit;

    @Value("${charts.output-dir:results}")
    private String outputDirectory;

    public DatasetPreflightRunner(BglDatasetPreflightService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {
        BglDatasetPreflightReport report = service.inspectAndWriteReport();
        BglEvaluationPlan plan = BglEvaluationScopeResolver.resolve(
                report,
                callerProvidedLimit,
                callerProvidedLimit ? requestedLimit : null
        );
        Path planFile = Path.of(outputDirectory).toAbsolutePath().normalize().resolve("bgl_evaluation_plan.json");
        Files.createDirectories(planFile.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(planFile.toFile(), plan);
        log.info("BGL dataset preflight completed: {}", report);
        log.info("BGL evaluation plan: {}", plan);
    }
}
