package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.BglEvaluationScope;

/** Preflight-derived record boundary consumed identically by both experiment methods. */
public record BglEvaluationPlan(
        BglEvaluationScope evaluationScope,
        boolean officialThesisRun,
        long fullDatasetLineCount,
        Long callerRequestedRecordLimit,
        long recordsToEvaluate
) {
}
