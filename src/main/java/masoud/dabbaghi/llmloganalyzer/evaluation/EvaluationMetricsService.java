package masoud.dabbaghi.llmloganalyzer.evaluation;

import lombok.RequiredArgsConstructor;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Calculates metrics with MongoDB-side counts.
 *
 * This avoids loading millions of LogEvaluation documents into JVM memory when generating charts.
 */
@Service
@RequiredArgsConstructor
public class EvaluationMetricsService {

    private final MongoTemplate mongoTemplate;

    public EvaluationMetrics calculate(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment,
            String promptVersion
    ) {
        Criteria base = baseCriteria(logType, aiModel, promptExperiment, promptVersion);

        long total = count(base);
        long invalidTotal = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("aiResult").is(ClassificationResult.INVALID));
        long validTotal = total - invalidTotal;

        long tp = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("realResult").is(ClassificationResult.ANOMALY)
                .and("aiResult").is(ClassificationResult.ANOMALY));

        long tn = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("realResult").is(ClassificationResult.NORMAL)
                .and("aiResult").is(ClassificationResult.NORMAL));

        long fp = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("realResult").is(ClassificationResult.NORMAL)
                .and("aiResult").is(ClassificationResult.ANOMALY));

        long fn = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("realResult").is(ClassificationResult.ANOMALY)
                .and("aiResult").is(ClassificationResult.NORMAL));

        long llmDecisionTotal = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("decisionSource").is(BglDecisionSource.LLM));

        long templateCacheDecisionTotal = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("decisionSource").is(BglDecisionSource.TEMPLATE_CACHE));

        long templateGuardDecisionTotal = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("decisionSource").is(BglDecisionSource.TEMPLATE_GUARD));

        long cacheHitTotal = count(baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                .and("cacheHit").is(true));

        double accuracy = safeDivide(tp + tn, validTotal);
        double precision = safeDivide(tp, tp + fp);
        double recall = safeDivide(tp, tp + fn);
        double f1Score = safeDivide(2 * precision * recall, precision + recall);
        double invalidRate = safeDivide(invalidTotal, total);

        double averageResponseTimeMs = averageResponseTime(base);
        double llmAverageResponseTimeMs = averageResponseTime(
                baseCriteria(logType, aiModel, promptExperiment, promptVersion)
                        .and("decisionSource").is(BglDecisionSource.LLM)
        );

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
                averageResponseTimeMs,
                llmAverageResponseTimeMs,
                llmDecisionTotal,
                templateCacheDecisionTotal,
                templateGuardDecisionTotal,
                cacheHitTotal
        );
    }

    private Criteria baseCriteria(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment,
            String promptVersion
    ) {
        return Criteria.where("logType").is(logType)
                .and("aiModel").is(aiModel)
                .and("promptExperiment").is(promptExperiment)
                .and("promptVersion").is(promptVersion);
    }

    private long count(Criteria criteria) {
        return mongoTemplate.count(Query.query(criteria), LogEvaluation.class);
    }

    private double averageResponseTime(Criteria criteria) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(criteria.and("responseTimeMs").exists(true)),
                Aggregation.group().avg("responseTimeMs").as("average")
        );

        AggregationResults<AverageValue> results = mongoTemplate.aggregate(
                aggregation,
                mongoTemplate.getCollectionName(LogEvaluation.class),
                AverageValue.class
        );

        AverageValue value = results.getUniqueMappedResult();
        return value == null || value.getAverage() == null ? 0 : value.getAverage();
    }

    private double safeDivide(double numerator, double denominator) {
        if (denominator == 0) {
            return 0;
        }
        return numerator / denominator;
    }

    public static class AverageValue {
        private Double average;

        public Double getAverage() {
            return average;
        }

        public void setAverage(Double average) {
            this.average = average;
        }
    }
}
