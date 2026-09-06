package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.config.OllamaProperties;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRunRepository;
import masoud.dabbaghi.llmloganalyzer.evaluation.LogEvaluationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BglExperimentRoutingTest {

    @TempDir
    Path tempDirectory;

    @Test
    void promptOnlyCallsLlmWhileHybridRoutesTheSameKnownPatternThroughGuardAndResetsCache() throws Exception {
        Path dataset = tempDirectory.resolve("BGL.log");
        Files.writeString(dataset,
                "SECRET_ANOMALY_LABEL 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 "
                        + "2005-06-03-15.42.50.363779 R02-M1-N0-C:J12-U11 "
                        + "RAS KERNEL FATAL data storage interrupt\n");

        CallModelAi model = mock(CallModelAi.class);
        LogEvaluationRepository evaluations = mock(LogEvaluationRepository.class);
        BglExperimentRunRepository runs = mock(BglExperimentRunRepository.class);
        TrackingCache cache = new TrackingCache();
        BglParser parser = new BglParser(
                model,
                evaluations,
                runs,
                cache,
                new BglTemplateLabelCollisionDetector(),
                new BglTemplateValidationService(),
                officialProperties()
        );
        configure(parser, dataset);

        when(model.resolveModelVersion()).thenReturn("sha256:test-model");
        when(model.classifyWithOllama(anyString(), anyString())).thenReturn(
                ModelClassificationResponse.valid(
                        "anomaly",
                        0.99,
                        "Explicit operational failure.",
                        "storage",
                        "{\"prediction\":\"anomaly\",\"confidence\":0.99,\"reason\":\"Explicit operational failure.\",\"category\":\"storage\"}"
                )
        );
        when(runs.save(any(BglExperimentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(parser, "guardEnabled", false);
        BglExperimentRun promptOnly = parser.logParser();

        assertEquals("PROMPT_ONLY_LLM", promptOnly.getClassificationMode());
        assertFalse(promptOnly.isTemplateGuardEnabled());
        assertEquals(1, promptOnly.getDirectLlmCalls());
        assertEquals(0, promptOnly.getDirectGuardDecisions());
        assertEquals(1, cache.size());

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(model).classifyWithOllama(input.capture(), anyString());
        assertFalse(input.getValue().contains("SECRET_ANOMALY_LABEL"));
        assertTrue(input.getValue().contains("data storage interrupt"));

        ReflectionTestUtils.setField(parser, "guardEnabled", true);
        BglExperimentRun hybrid = parser.logParser();

        assertEquals("HYBRID_GUARD_AND_LLM", hybrid.getClassificationMode());
        assertTrue(hybrid.isTemplateGuardEnabled());
        assertEquals(0, hybrid.getDirectLlmCalls());
        assertEquals(1, hybrid.getDirectGuardDecisions());
        assertEquals(2, cache.resetCount);
        assertEquals(promptOnly.getModelName(), hybrid.getModelName());
        assertEquals(promptOnly.getModelVersion(), hybrid.getModelVersion());
        assertEquals(promptOnly.getTemperature(), hybrid.getTemperature());
        assertEquals(promptOnly.getTopP(), hybrid.getTopP());
        assertEquals(promptOnly.getSeed(), hybrid.getSeed());
        assertEquals(promptOnly.getNumCtx(), hybrid.getNumCtx());
        assertEquals(promptOnly.getFormat(), hybrid.getFormat());
        assertEquals(promptOnly.isThinkingEnabled(), hybrid.isThinkingEnabled());
        verify(model, times(1)).classifyWithOllama(anyString(), anyString());
    }

    @Test
    void maxRecordsStopsInputAndLabelCollisionRemainsAuditOnly() throws Exception {
        Path dataset = tempDirectory.resolve("BGL-capped.log");
        String sharedRecord = "1117838570 2005.06.03 R02-M1-N0-C:J12-U11 "
                + "2005-06-03-15.42.50.363779 R02-M1-N0-C:J12-U11 "
                + "RAS KERNEL INFO routine diagnostic status\n";
        Files.writeString(dataset,
                "- " + sharedRecord
                        + "CONFLICTING_HIDDEN_LABEL " + sharedRecord
                        + "- 1117838571 2005.06.03 R02-M1-N0-C:J12-U11 "
                        + "2005-06-03-15.42.51.363779 R02-M1-N0-C:J12-U11 "
                        + "RAS KERNEL INFO third record must not be parsed\n");

        CallModelAi model = mock(CallModelAi.class);
        LogEvaluationRepository evaluations = mock(LogEvaluationRepository.class);
        BglExperimentRunRepository runs = mock(BglExperimentRunRepository.class);
        TrackingCache cache = new TrackingCache();
        BglTemplateLabelCollisionDetector detector = new BglTemplateLabelCollisionDetector();
        BglParser parser = new BglParser(
                model,
                evaluations,
                runs,
                cache,
                detector,
                new BglTemplateValidationService(),
                officialProperties()
        );
        configure(parser, dataset);
        ReflectionTestUtils.setField(parser, "maxRecords", 2L);
        ReflectionTestUtils.setField(parser, "guardEnabled", false);

        when(model.resolveModelVersion()).thenReturn("sha256:test-model");
        when(model.classifyWithOllama(anyString(), anyString())).thenReturn(
                ModelClassificationResponse.valid(
                        "normal",
                        0.95,
                        "Routine diagnostic status.",
                        "diagnostic",
                        "{\"prediction\":\"normal\",\"confidence\":0.95,\"reason\":\"Routine diagnostic status.\",\"category\":\"diagnostic\"}"
                )
        );
        when(runs.save(any(BglExperimentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BglExperimentRun run = parser.logParser();

        assertEquals(2, run.getMaxRecords());
        assertEquals(2, run.getRawLineCount());
        assertEquals(2, run.getParsedLineCount());
        assertEquals(1, run.getDirectLlmCalls());
        assertEquals(1, run.getTotalCacheHits());
        assertEquals(1, run.getObservedTemplateCount());
        assertEquals(1, run.getTemplateLabelConflictCount());
        assertEquals(1, cache.size());
        verify(model, times(1)).classifyWithOllama(anyString(), anyString());
    }

    private void configure(BglParser parser, Path dataset) {
        ReflectionTestUtils.setField(parser, "bglPath", dataset.toString());
        ReflectionTestUtils.setField(parser, "maxRecords", 1L);
        ReflectionTestUtils.setField(parser, "cacheEnabled", true);
        ReflectionTestUtils.setField(parser, "validateBeforeCache", true);
        ReflectionTestUtils.setField(parser, "includeMetadata", true);
        ReflectionTestUtils.setField(parser, "gitCommit", "test-commit");
        ReflectionTestUtils.setField(parser, "developmentDataset", "BGL_2k.log");
        ReflectionTestUtils.setField(parser, "developmentDataNote", "Development provenance test");
        ReflectionTestUtils.setField(parser, "persistenceBatchSize", 1000);
    }

    private OllamaProperties officialProperties() {
        OllamaProperties properties = new OllamaProperties();
        properties.setModelName("qwen3.5:35b");
        properties.setFormat("json");
        properties.setThinking(false);
        properties.getOptions().setTemperature(0.0);
        properties.getOptions().setTopP(0.9);
        properties.getOptions().setRepeatPenalty(1.0);
        properties.getOptions().setSeed(42);
        properties.getOptions().setNumCtx(8192);
        properties.getOptions().setNumPredict(160);
        properties.getTimeouts().setConnect(Duration.ofSeconds(10));
        properties.getTimeouts().setResponse(Duration.ofMinutes(15));
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoff(Duration.ofSeconds(1));
        properties.getRetry().setMaxBackoff(Duration.ofSeconds(5));
        return properties;
    }

    private static final class TrackingCache extends BglTemplateClassificationCache {
        private int resetCount;

        @Override
        public void resetForRun() {
            super.resetForRun();
            resetCount++;
        }
    }
}
