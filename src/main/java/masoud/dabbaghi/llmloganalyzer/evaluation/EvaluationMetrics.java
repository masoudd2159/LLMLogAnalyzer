package masoud.dabbaghi.llmloganalyzer.evaluation;

import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;

/**
 * Aggregated evaluation metrics.
 * <p>
 * The values are produced from MongoDB-side aggregation/count queries, not by loading
 * all LogEvaluation records into JVM memory.
 */
public record EvaluationMetrics(
        PromptExperiment promptExperiment,
        String promptVersion,
        String runId,
        String selectionDescription,

        long total,
        long validTotal,
        long invalidTotal,

        long truePositive,
        long trueNegative,
        long falsePositive,
        long falseNegative,

        double accuracy,
        double precision,
        double recall,
        double f1Score,

        double invalidRate,
        double averageResponseTimeMs,
        double averageLlmResponseTimeMs,

        long llmDecisionCount,
        long templateCacheDecisionCount,
        long templateCacheFromLlmDecisionCount,
        long templateCacheFromGuardDecisionCount,
        long templateGuardDecisionCount,
        long cacheHitCount,

        /*
         * Number of unique cacheable template keys in the selected evaluation scope.
         * This is the MongoDB-side equivalent of the final in-memory template cache size.
         */
        long templateCacheSize,
        long templateCacheSizeFromLlm,
        long templateCacheSizeFromGuard,

        long processingDurationMs,
        double throughputLinesPerSecond
) {
}
