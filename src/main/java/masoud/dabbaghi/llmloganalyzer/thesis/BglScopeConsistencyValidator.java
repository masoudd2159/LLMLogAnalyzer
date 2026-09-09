package masoud.dabbaghi.llmloganalyzer.thesis;

import masoud.dabbaghi.llmloganalyzer.evaluation.BglEvaluationScope;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;

import java.util.LinkedHashMap;
import java.util.Map;

/** Scope-aware coverage checks shared by artifact generation and unit tests. */
public final class BglScopeConsistencyValidator {
    private BglScopeConsistencyValidator() {
    }

    public static Map<String, Object> validate(
            BglExperimentRun run,
            long preflightRawLines,
            long preflightParsedLines,
            long preflightParseErrors,
            long evaluationTotal,
            Long firstRecordIndex,
            Long lastRecordIndex
    ) {
        Map<String, Object> checks = new LinkedHashMap<>();
        require(checks, "scopeIsExplicit", run.getEvaluationScope() == BglEvaluationScope.FULL_DATASET
                || run.getEvaluationScope() == BglEvaluationScope.LIMITED_FIRST_N);
        require(checks, "fullDatasetLineCountMatchesPreflight", run.getFullDatasetLineCount() == preflightRawLines);
        require(checks, "rawAccounting", run.getParsedLineCount() + run.getParseErrorCount() == run.getRawLineCount());
        require(checks, "parsedRecordsEqualEvaluations", run.getParsedLineCount() == evaluationTotal);
        require(checks, "coveragePercentageMatchesCounts", close(
                run.getEvaluationCoveragePercentage(), percentage(run.getParsedLineCount(), preflightRawLines)));

        if (run.getEvaluationScope() == BglEvaluationScope.FULL_DATASET) {
            require(checks, "officialThesisRun", run.isOfficialThesisRun());
            require(checks, "preflightHasZeroParseErrors", preflightParseErrors == 0);
            require(checks, "preflightParsedEveryRawLine", preflightParsedLines == preflightRawLines);
            require(checks, "processedEveryRawLine", run.getRawLineCount() == preflightRawLines);
            require(checks, "parsedEveryRawLine", run.getParsedLineCount() == preflightParsedLines);
            require(checks, "runHasZeroParseErrors", run.getParseErrorCount() == 0);
            require(checks, "coverageIs100Percent", close(run.getEvaluationCoveragePercentage(), 100.0));
            require(checks, "firstRecordIndexIsOne", Long.valueOf(1).equals(firstRecordIndex));
            require(checks, "lastRecordIndexIsFullDatasetSize", Long.valueOf(preflightRawLines).equals(lastRecordIndex));
        } else {
            require(checks, "notOfficialThesisRun", !run.isOfficialThesisRun());
            require(checks, "recordLimitWasExplicit", run.isRecordLimitExplicit());
            require(checks, "requestedLimitIsPositive", run.getMaxRecords() > 0);
            require(checks, "requestedLimitDoesNotExceedDataset", run.getMaxRecords() <= preflightRawLines);
            require(checks, "processedRequestedLimit", run.getRawLineCount() == run.getMaxRecords());
            require(checks, "lastRecordIndexWithinRequestedLimit", lastRecordIndex == null || lastRecordIndex <= run.getMaxRecords());
        }
        return checks;
    }

    private static double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0 : numerator * 100.0 / denominator;
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 1e-9;
    }

    private static void require(Map<String, Object> checks, String name, boolean value) {
        checks.put(name, value);
        if (!value) {
            throw new IllegalStateException("Scope consistency check failed: " + name);
        }
    }
}
