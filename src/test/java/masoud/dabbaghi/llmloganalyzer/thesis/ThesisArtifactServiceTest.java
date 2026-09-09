package masoud.dabbaghi.llmloganalyzer.thesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRunRepository;
import masoud.dabbaghi.llmloganalyzer.evaluation.EvaluationMetricsService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ThesisArtifactServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exactRunExportNeverFallsBackToHistoricalOrLatestRuns() throws Exception {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BglExperimentRunRepository runs = mock(BglExperimentRunRepository.class);
        BglExperimentRun selected = BglExperimentRun.builder().runId("current-run").status("FAILED").build();
        when(runs.findById("current-run")).thenReturn(Optional.of(selected));
        ThesisArtifactService service = service(mongo, runs);
        ReflectionTestUtils.setField(service, "outputDirectory", temporaryDirectory.toString());

        assertThrows(IllegalStateException.class, () -> service.exportCompletedRun("current-run"));
        JsonNode exported = new ObjectMapper().findAndRegisterModules()
                .readTree(temporaryDirectory.resolve("bgl_experiment_runs.json").toFile());
        assertEquals("current-run", exported.path("runId").asText());
        assertFalse(exported.isArray());
        verify(runs).findById("current-run");
        verify(runs, never()).findAll();
    }

    @Test
    void emptyInvalidOutputExportIsAValidEmptyGzipJsonlFile() throws Exception {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.getCollectionName(any(Class.class))).thenReturn("log_evaluations");
        when(mongo.stream(any(Query.class), eq(Document.class), eq("log_evaluations")))
                .thenReturn(Stream.empty());
        ThesisArtifactService service = service(mongo, mock(BglExperimentRunRepository.class));
        Path target = temporaryDirectory.resolve("invalid_outputs.jsonl.gz");

        service.exportJsonLines("run-with-no-invalid", target, true);

        assertTrue(Files.size(target) > 0);
        try (InputStream input = new GZIPInputStream(Files.newInputStream(target))) {
            assertEquals(0, input.readAllBytes().length);
        }
    }

    private ThesisArtifactService service(MongoTemplate mongo, BglExperimentRunRepository runs) {
        return new ThesisArtifactService(
                mongo,
                runs,
                mock(EvaluationMetricsService.class),
                new ObjectMapper().findAndRegisterModules()
        );
    }
}
