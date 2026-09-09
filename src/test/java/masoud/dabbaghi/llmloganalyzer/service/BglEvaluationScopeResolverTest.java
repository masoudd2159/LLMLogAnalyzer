package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.BglEvaluationScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BglEvaluationScopeResolverTest {

    @Test
    void unsetLimitUsesPreflightCountForOfficialFullDatasetRun() {
        BglEvaluationPlan plan = BglEvaluationScopeResolver.resolve(report(7, 7, 0), false, null);

        assertEquals(BglEvaluationScope.FULL_DATASET, plan.evaluationScope());
        assertTrue(plan.officialThesisRun());
        assertEquals(7, plan.fullDatasetLineCount());
        assertEquals(7, plan.recordsToEvaluate());
        assertNull(plan.callerRequestedRecordLimit());
    }

    @Test
    void explicitSmallerLimitIsLimitedButEqualLimitIsFullDataset() {
        BglEvaluationPlan limited = BglEvaluationScopeResolver.resolve(report(7, 7, 0), true, 3L);
        assertEquals(BglEvaluationScope.LIMITED_FIRST_N, limited.evaluationScope());
        assertFalse(limited.officialThesisRun());
        assertEquals(3, limited.recordsToEvaluate());

        BglEvaluationPlan full = BglEvaluationScopeResolver.resolve(report(7, 7, 0), true, 7L);
        assertEquals(BglEvaluationScope.FULL_DATASET, full.evaluationScope());
        assertTrue(full.officialThesisRun());
    }

    @Test
    void rejectsLimitGreaterThanPreflightDatasetWithoutClamping() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BglEvaluationScopeResolver.resolve(report(7, 7, 0), true, 8L));
        assertTrue(error.getMessage().contains("will not be silently clamped"));
    }

    @Test
    void fullDatasetRequiresZeroParseErrorsAndCompleteParserCoverage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BglEvaluationScopeResolver.resolve(report(7, 6, 1), false, null));
        assertTrue(error.getMessage().contains("requires zero BGL parser errors"));
        assertTrue(error.getMessage().contains("Hybrid inference was not started"));

        assertThrows(IllegalStateException.class,
                () -> BglEvaluationScopeResolver.resolve(report(7, 6, 0), false, null));

        assertDoesNotThrow(() -> BglEvaluationScopeResolver.resolve(report(7, 7, 0), false, null));
    }

    @Test
    void limitedRunMayProceedWithReportedParserErrorsAndZeroLineDatasetIsRejected() {
        BglEvaluationPlan limited = BglEvaluationScopeResolver.resolve(report(7, 6, 1), true, 3L);
        assertEquals(BglEvaluationScope.LIMITED_FIRST_N, limited.evaluationScope());

        assertThrows(IllegalStateException.class,
                () -> BglEvaluationScopeResolver.resolve(report(0, 0, 0), false, null));
    }

    private BglDatasetPreflightReport report(long raw, long parsed, long errors) {
        return new BglDatasetPreflightReport(
                "BGL.log", "sha256", 100, raw, parsed, errors, parsed, 0, Instant.EPOCH);
    }
}
