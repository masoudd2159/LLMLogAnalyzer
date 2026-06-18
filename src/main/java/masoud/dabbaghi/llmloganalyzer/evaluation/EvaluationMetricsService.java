package masoud.dabbaghi.llmloganalyzer.evaluation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calculates metrics using MongoDB-side counts/aggregations.
 * <p>
 * This avoids loading millions of LogEvaluation documents into JVM memory when charts
 * are generated for the full BGL dataset.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationMetricsService {

    private final MongoTemplate mongoTemplate;

    public EvaluationMetrics calculate(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment,
            String promptVersion
    ) {
        Criteria criteria = buildCriteria(logType, aiModel, promptExperiment, promptVersion);
        String description = "promptVersion=" + promptVersion;
        return calculateFromCriteria(promptExperiment, promptVersion, description, criteria);
    }

    public EvaluationMetrics calculateForCharts(
            LogType logType,
            AiModel aiModel,
            PromptExperiment currentExperiment,
            String currentPromptVersion,
            String chartScope
    ) {
        String normalizedScope = chartScope == null
                ? "all"
                : chartScope.trim().toLowerCase(Locale.ROOT);

        return switch (normalizedScope) {
            case "current" -> calculate(logType, aiModel, currentExperiment, currentPromptVersion);
            case "latest" -> calculateLatestAvailable(logType, aiModel, currentExperiment, currentPromptVersion);
            case "auto" -> calculateAuto(logType, aiModel, currentExperiment, currentPromptVersion);
            case "all" -> calculateAllVersions(logType, aiModel);
            default -> {
                log.warn("Unknown charts.data.scope='{}'. Falling back to all.", chartScope);
                yield calculateAllVersions(logType, aiModel);
            }
        };
    }

    public EvaluationMetrics calculateAuto(
            LogType logType,
            AiModel aiModel,
            PromptExperiment currentExperiment,
            String currentPromptVersion
    ) {
        EvaluationMetrics current = calculate(logType, aiModel, currentExperiment, currentPromptVersion);
        if (current.total() > 0) {
            return current;
        }

        EvaluationMetrics latest = calculateLatestAvailable(logType, aiModel, currentExperiment, currentPromptVersion);
        if (latest.total() > 0) {
            log.warn(
                    "No chart data found for current promptVersion={}. Falling back to latest available promptVersion={}",
                    currentPromptVersion,
                    latest.promptVersion()
            );
            return latest;
        }

        EvaluationMetrics all = calculateAllVersions(logType, aiModel);
        if (all.total() > 0) {
            log.warn(
                    "No chart data found for current/latest prompt version. Falling back to all BGL/OLLAMA evaluation records."
            );
        }
        return all;
    }

    public EvaluationMetrics calculateLatestAvailable(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment,
            String currentPromptVersion
    ) {
        String latestPromptVersion = findLatestPromptVersion(logType, aiModel, promptExperiment);
        if (latestPromptVersion == null || latestPromptVersion.isBlank()) {
            return calculate(logType, aiModel, promptExperiment, currentPromptVersion);
        }
        return calculate(logType, aiModel, promptExperiment, latestPromptVersion);
    }

    public EvaluationMetrics calculateAllVersions(LogType logType, AiModel aiModel) {
        Criteria criteria = buildCriteria(logType, aiModel, null, null);
        return calculateFromCriteria(
                null,
                "ALL_PROMPT_VERSIONS",
                "scope=all BGL/OLLAMA records",
                criteria
        );
    }

    private EvaluationMetrics calculateFromCriteria(
            PromptExperiment promptExperiment,
            String promptVersion,
            String selectionDescription,
            Criteria criteria
    ) {
        long total = count(criteria);
        long invalidTotal = count(and(criteria, Criteria.where("aiResult").is(ClassificationResult.INVALID.name())));
        long validTotal = Math.max(0, total - invalidTotal);

        long tp = count(and(criteria,
                Criteria.where("realResult").is(ClassificationResult.ANOMALY.name()),
                Criteria.where("aiResult").is(ClassificationResult.ANOMALY.name())
        ));

        long tn = count(and(criteria,
                Criteria.where("realResult").is(ClassificationResult.NORMAL.name()),
                Criteria.where("aiResult").is(ClassificationResult.NORMAL.name())
        ));

        long fp = count(and(criteria,
                Criteria.where("realResult").is(ClassificationResult.NORMAL.name()),
                Criteria.where("aiResult").is(ClassificationResult.ANOMALY.name())
        ));

        long fn = count(and(criteria,
                Criteria.where("realResult").is(ClassificationResult.ANOMALY.name()),
                Criteria.where("aiResult").is(ClassificationResult.NORMAL.name())
        ));

        double accuracy = safeDivide(tp + tn, validTotal);
        double precision = safeDivide(tp, tp + fp);
        double recall = safeDivide(tp, tp + fn);
        double f1Score = safeDivide(2 * precision * recall, precision + recall);
        double invalidRate = safeDivide(invalidTotal, total);

        double averageLineResponseTime = average(criteria, "responseTimeMs");
        double averageLlmResponseTime = average(
                and(criteria,
                        Criteria.where("decisionSource").is(BglDecisionSource.LLM.name()),
                        Criteria.where("responseTimeMs").gt(0)
                ),
                "responseTimeMs"
        );

        long llmCount = count(and(criteria, Criteria.where("decisionSource").is(BglDecisionSource.LLM.name())));
        long cacheCount = count(and(criteria, Criteria.where("decisionSource").is(BglDecisionSource.TEMPLATE_CACHE.name())));
        long cacheFromLlmCount = count(and(criteria,
                Criteria.where("decisionSource").is(BglDecisionSource.TEMPLATE_CACHE.name()),
                Criteria.where("cacheSource").is(BglDecisionSource.LLM.name())
        ));
        long cacheFromGuardCount = count(and(criteria,
                Criteria.where("decisionSource").is(BglDecisionSource.TEMPLATE_CACHE.name()),
                Criteria.where("cacheSource").is(BglDecisionSource.TEMPLATE_GUARD.name())
        ));
        long guardCount = count(and(criteria, Criteria.where("decisionSource").is(BglDecisionSource.TEMPLATE_GUARD.name())));
        long cacheHitCount = count(and(criteria, Criteria.where("cacheHit").is(true)));

        long templateCacheSize = countDistinctTemplateKeys(
                and(criteria, Criteria.where("cacheable").is(true))
        );
        long templateCacheSizeFromLlm = countDistinctTemplateKeys(and(criteria,
                Criteria.where("cacheable").is(true),
                Criteria.where("decisionSource").is(BglDecisionSource.LLM.name())
        ));
        long templateCacheSizeFromGuard = countDistinctTemplateKeys(and(criteria,
                Criteria.where("cacheable").is(true),
                Criteria.where("decisionSource").is(BglDecisionSource.TEMPLATE_GUARD.name())
        ));

        log.info(
                "Chart metrics: selection={}, total={}, valid={}, invalid={}, TP={}, TN={}, FP={}, FN={}, LLM={}, Cache={}, CacheFromLLM={}, CacheFromGuard={}, Guard={}, CacheHits={}, TemplateCacheSize={}, TemplateCacheSizeFromLLM={}, TemplateCacheSizeFromGuard={}, lineAvgMs={}, llmAvgMs={}",
                selectionDescription,
                total,
                validTotal,
                invalidTotal,
                tp,
                tn,
                fp,
                fn,
                llmCount,
                cacheCount,
                cacheFromLlmCount,
                cacheFromGuardCount,
                guardCount,
                cacheHitCount,
                templateCacheSize,
                templateCacheSizeFromLlm,
                templateCacheSizeFromGuard,
                averageLineResponseTime,
                averageLlmResponseTime
        );

        return new EvaluationMetrics(
                promptExperiment,
                promptVersion,
                selectionDescription,
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
                averageLineResponseTime,
                averageLlmResponseTime,
                llmCount,
                cacheCount,
                cacheFromLlmCount,
                cacheFromGuardCount,
                guardCount,
                cacheHitCount,
                templateCacheSize,
                templateCacheSizeFromLlm,
                templateCacheSizeFromGuard
        );
    }

    private String findLatestPromptVersion(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment
    ) {
        Query query = Query.query(buildCriteria(logType, aiModel, promptExperiment, null))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(1);
        query.fields().include("promptVersion").include("createdAt");

        LogEvaluation latest = mongoTemplate.findOne(query, LogEvaluation.class);
        return latest == null ? null : latest.getPromptVersion();
    }

    private Criteria buildCriteria(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment,
            String promptVersion
    ) {
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("logType").is(logType.name()));
        criteria.add(Criteria.where("aiModel").is(aiModel.name()));

        if (promptExperiment != null) {
            criteria.add(Criteria.where("promptExperiment").is(promptExperiment.name()));
        }

        if (promptVersion != null && !promptVersion.isBlank()) {
            criteria.add(Criteria.where("promptVersion").is(promptVersion));
        }

        return new Criteria().andOperator(criteria.toArray(new Criteria[0]));
    }

    private Criteria and(Criteria base, Criteria... extraCriteria) {
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(base);
        if (extraCriteria != null) {
            criteria.addAll(List.of(extraCriteria));
        }
        return new Criteria().andOperator(criteria.toArray(new Criteria[0]));
    }

    private long count(Criteria criteria) {
        return mongoTemplate.count(Query.query(criteria), LogEvaluation.class);
    }

    private long countDistinctTemplateKeys(Criteria criteria) {
        List<String> templateKeys = mongoTemplate.findDistinct(
                Query.query(criteria),
                "templateKey",
                LogEvaluation.class,
                String.class
        );

        return templateKeys.stream()
                .filter(templateKey -> templateKey != null && !templateKey.isBlank())
                .count();
    }

    private double average(Criteria criteria, String fieldName) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(criteria),
                Aggregation.group().avg(fieldName).as("avg")
        );

        Document result = mongoTemplate
                .aggregate(aggregation, mongoTemplate.getCollectionName(LogEvaluation.class), Document.class)
                .getUniqueMappedResult();

        if (result == null || result.get("avg") == null) {
            return 0;
        }

        Object avg = result.get("avg");
        if (avg instanceof Number number) {
            return number.doubleValue();
        }
        return 0;
    }

    private double safeDivide(double numerator, double denominator) {
        if (denominator == 0) {
            return 0;
        }
        return numerator / denominator;
    }
}
