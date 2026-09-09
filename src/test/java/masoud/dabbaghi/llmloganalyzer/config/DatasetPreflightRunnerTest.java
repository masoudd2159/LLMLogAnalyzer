package masoud.dabbaghi.llmloganalyzer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import masoud.dabbaghi.llmloganalyzer.service.BglDatasetPreflightReport;
import masoud.dabbaghi.llmloganalyzer.service.BglDatasetPreflightService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatasetPreflightRunnerTest {
    @TempDir Path outputDirectory;

    @Test
    void unsetCallerLimitWritesFullDatasetPlanUsingPreflightRawLines() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BglDatasetPreflightService service = mock(BglDatasetPreflightService.class);
        when(service.inspectAndWriteReport()).thenReturn(
                new BglDatasetPreflightReport("BGL.log", "sha", 10, 4, 4, 0, 3, 1, Instant.EPOCH));
        DatasetPreflightRunner runner = new DatasetPreflightRunner(service, mapper);
        ReflectionTestUtils.setField(runner, "callerProvidedLimit", false);
        ReflectionTestUtils.setField(runner, "requestedLimit", -1L);
        ReflectionTestUtils.setField(runner, "outputDirectory", outputDirectory.toString());

        runner.run();

        JsonNode plan = mapper.readTree(outputDirectory.resolve("bgl_evaluation_plan.json").toFile());
        assertEquals("FULL_DATASET", plan.path("evaluationScope").asText());
        assertTrue(plan.path("officialThesisRun").asBoolean());
        assertEquals(4, plan.path("fullDatasetLineCount").asLong());
        assertEquals(4, plan.path("recordsToEvaluate").asLong());
        assertTrue(plan.path("callerRequestedRecordLimit").isNull());
    }
}
