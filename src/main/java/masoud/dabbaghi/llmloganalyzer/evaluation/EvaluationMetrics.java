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
        long templateGuardDecisionCount,
        long cacheHitCount
) {
}
