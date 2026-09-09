package masoud.dabbaghi.llmloganalyzer.thesis;

import masoud.dabbaghi.llmloganalyzer.evaluation.BglEvaluationScope;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BglScopeConsistencyValidatorTest {

    @Test
    void fullDatasetRequiresOneBasedCompleteHundredPercentCoverage() {
        BglExperimentRun run = run(BglEvaluationScope.FULL_DATASET, true, 5, 5, 5, 0, 100.0);

        assertDoesNotThrow(() -> BglScopeConsistencyValidator.validate(run, 5, 5, 0, 5, 1L, 5L));
        assertThrows(IllegalStateException.class,
                () -> BglScopeConsistencyValidator.validate(run, 5, 5, 0, 5, 1L, 4L));
    }

    @Test
    void fullDatasetRejectsIncompleteCoverageOrParserErrors() {
        BglExperimentRun incomplete = run(BglEvaluationScope.FULL_DATASET, true, 5, 4, 4, 0, 80.0);
        assertThrows(IllegalStateException.class,
                () -> BglScopeConsistencyValidator.validate(incomplete, 5, 5, 0, 4, 1L, 4L));

        BglExperimentRun parseError = run(BglEvaluationScope.FULL_DATASET, true, 5, 5, 4, 1, 100.0);
        assertThrows(IllegalStateException.class,
                () -> BglScopeConsistencyValidator.validate(parseError, 5, 4, 1, 4, 1L, 4L));
    }

    @Test
    void limitedFirstNStillUsesRequestedBoundaryAndParsedEvaluationTotal() {
        BglExperimentRun run = run(BglEvaluationScope.LIMITED_FIRST_N, false, 2, 5, 2, 0, 40.0);

        assertDoesNotThrow(() -> BglScopeConsistencyValidator.validate(run, 5, 5, 0, 2, 1L, 2L));
    }

    private BglExperimentRun run(BglEvaluationScope scope, boolean official, long requested,
                                 long full, long parsed, long errors, double coverage) {
        return BglExperimentRun.builder()
                .evaluationScope(scope).officialThesisRun(official).maxRecords(requested)
                .recordLimitExplicit(scope == BglEvaluationScope.LIMITED_FIRST_N)
                .fullDatasetLineCount(full).rawLineCount(parsed + errors).parsedLineCount(parsed)
                .parseErrorCount(errors).evaluationCoveragePercentage(coverage).build();
    }
}
