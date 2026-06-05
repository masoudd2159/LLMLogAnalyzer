package masoud.dabbaghi.llmloganalyzer.evaluation;

import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;

public record EvaluationMetrics(
        PromptExperiment promptExperiment,
        String promptVersion,

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
        double averageResponseTimeMs
) {
}