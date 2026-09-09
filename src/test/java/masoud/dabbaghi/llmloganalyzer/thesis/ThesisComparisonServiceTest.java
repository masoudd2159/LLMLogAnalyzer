package masoud.dabbaghi.llmloganalyzer.thesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglEvaluationScope;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThesisComparisonServiceTest {
    @Test
    void calculatesCountAndRateDeltasWithoutMeaninglessDivision() {
        var count = ThesisComparisonService.calculateDelta(80, 100, false);
        assertEquals(-20, count.absoluteDelta());
        assertEquals(-.2, count.relativeDifference());
        assertNull(count.percentagePointDelta());

        var rate = ThesisComparisonService.calculateDelta(.91, .89, true);
        assertEquals(.02, rate.absoluteDelta(), 1e-12);
        assertNull(rate.relativeDifference());
        assertEquals(2.0, rate.percentagePointDelta(), 1e-12);
        assertNull(ThesisComparisonService.calculateDelta(5, 0, false).relativeDifference());
    }

    @Test
    void rejectsMismatchedFrozenDatasetOrModelIdentity() {
        ThesisComparisonService service = new ThesisComparisonService(new ObjectMapper());
        BglExperimentRun hybrid = pairedRun(true);
        BglExperimentRun prompt = pairedRun(false);
        service.validatePair(hybrid, prompt);

        prompt.setDatasetSha256("different");
        assertThrows(IllegalStateException.class, () -> service.validatePair(hybrid, prompt));
        prompt.setDatasetSha256("dataset-sha");
        prompt.setModelDigest("different-model");
        assertThrows(IllegalStateException.class, () -> service.validatePair(hybrid, prompt));
    }

    @Test
    void rejectsDifferentEvaluationScopesOrProcessedCounts() {
        ThesisComparisonService service = new ThesisComparisonService(new ObjectMapper());
        BglExperimentRun hybrid = pairedRun(true);
        BglExperimentRun prompt = pairedRun(false);

        prompt.setEvaluationScope(BglEvaluationScope.LIMITED_FIRST_N);
        assertThrows(IllegalStateException.class, () -> service.validatePair(hybrid, prompt));

        prompt.setEvaluationScope(BglEvaluationScope.FULL_DATASET);
        prompt.setRawLineCount(99);
        assertThrows(IllegalStateException.class, () -> service.validatePair(hybrid, prompt));
    }

    private BglExperimentRun pairedRun(boolean hybrid) {
        return BglExperimentRun.builder()
                .runId(hybrid ? "h" : "p").status("COMPLETED").experimentBatchId("batch")
                .databaseName(hybrid ? "hybrid" : "prompt_only").methodOrder(hybrid ? 1 : 2)
                .classificationMode(hybrid ? "HYBRID_GUARD_AND_LLM" : "PROMPT_ONLY_LLM")
                .datasetPath("BGL.log").datasetSha256("dataset-sha")
                .maxRecords(100).evaluationScope(BglEvaluationScope.FULL_DATASET)
                .recordLimitExplicit(false).officialThesisRun(true).fullDatasetLineCount(100)
                .rawLineCount(100).parsedLineCount(100).parseErrorCount(0).evaluationCoveragePercentage(100.0)
                .modelName("qwen3.5:35b").modelDigest("model-sha").temperature(0).topP(.9)
                .repeatPenalty(1).seed(42).numCtx(8192).numPredict(160).format("json")
                .thinkingEnabled(false).templateCacheEnabled(true).templateGuardEnabled(hybrid)
                .includeMetadataInTemplateKey(true).gitCommit("commit").build();
    }
}
