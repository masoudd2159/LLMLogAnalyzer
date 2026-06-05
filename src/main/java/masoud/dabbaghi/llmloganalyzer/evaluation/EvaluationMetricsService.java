package masoud.dabbaghi.llmloganalyzer.evaluation;

import lombok.RequiredArgsConstructor;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EvaluationMetricsService {

    private final LogEvaluationRepository repository;

    public EvaluationMetrics calculate(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment,
            String promptVersion
    ) {
        List<LogEvaluation> evaluations =
                repository.findByLogTypeAndAiModelAndPromptExperimentAndPromptVersion(
                        logType,
                        aiModel,
                        promptExperiment,
                        promptVersion
                );

        return calculateFromList(promptExperiment, promptVersion, evaluations);
    }

    private EvaluationMetrics calculateFromList(
            PromptExperiment promptExperiment,
            String promptVersion,
            List<LogEvaluation> evaluations
    ) {
        long total = evaluations.size();

        long invalidTotal = evaluations.stream()
                .filter(e -> e.getAiResult() == ClassificationResult.INVALID)
                .count();

        long validTotal = total - invalidTotal;

        long tp = evaluations.stream()
                .filter(this::isTruePositive)
                .count();

        long tn = evaluations.stream()
                .filter(this::isTrueNegative)
                .count();

        long fp = evaluations.stream()
                .filter(this::isFalsePositive)
                .count();

        long fn = evaluations.stream()
                .filter(this::isFalseNegative)
                .count();

        double accuracy = safeDivide(tp + tn, validTotal);
        double precision = safeDivide(tp, tp + fp);
        double recall = safeDivide(tp, tp + fn);
        double f1Score = safeDivide(2 * precision * recall, precision + recall);
        double invalidRate = safeDivide(invalidTotal, total);

        double averageResponseTimeMs = evaluations.stream()
                .filter(e -> e.getResponseTimeMs() != null)
                .mapToLong(LogEvaluation::getResponseTimeMs)
                .average()
                .orElse(0);

        return new EvaluationMetrics(
                promptExperiment,
                promptVersion,
                total,
                validTotal,
                invalidTotal,
                tp,
                tn,
                fp,
                fn,
                accuracy,
                precision,
                recall,
                f1Score,
                invalidRate,
                averageResponseTimeMs
        );
    }

    private boolean isTruePositive(LogEvaluation e) {
        return e.getRealResult() == ClassificationResult.ANOMALY
                && e.getAiResult() == ClassificationResult.ANOMALY;
    }

    private boolean isTrueNegative(LogEvaluation e) {
        return e.getRealResult() == ClassificationResult.NORMAL
                && e.getAiResult() == ClassificationResult.NORMAL;
    }

    private boolean isFalsePositive(LogEvaluation e) {
        return e.getRealResult() == ClassificationResult.NORMAL
                && e.getAiResult() == ClassificationResult.ANOMALY;
    }

    private boolean isFalseNegative(LogEvaluation e) {
        return e.getRealResult() == ClassificationResult.ANOMALY
                && e.getAiResult() == ClassificationResult.NORMAL;
    }

    private double safeDivide(double numerator, double denominator) {
        if (denominator == 0) {
            return 0;
        }
        return numerator / denominator;
    }
}