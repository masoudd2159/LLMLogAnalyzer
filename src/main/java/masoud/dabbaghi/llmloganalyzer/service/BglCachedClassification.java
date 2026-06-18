package masoud.dabbaghi.llmloganalyzer.service;

import lombok.Getter;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglDecisionSource;
import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cached template-level classification.
 * <p>
 * One instance represents one unique normalized template that was classified once.
 * Later raw logs with the same template can reuse this result without another LLM call.
 */
public final class BglCachedClassification {

    @Getter
    private final String templateKey;
    @Getter
    private final String normalizedTemplate;
    @Getter
    private final ClassificationResult prediction;
    @Getter
    private final BglDecisionSource originalDecisionSource;
    @Getter
    private final String matchedTemplatePattern;
    @Getter
    private final PromptExperiment promptExperiment;
    @Getter
    private final String promptVersion;
    @Getter
    private final String rawModelOutput;
    @Getter
    private final boolean validModelOutput;
    @Getter
    private final boolean cacheable;
    @Getter
    private final String validationStatus;
    @Getter
    private final String validationReason;
    @Getter
    private final LocalDateTime createdAt;
    private final AtomicInteger hitCount = new AtomicInteger(0);

    public BglCachedClassification(
            String templateKey,
            String normalizedTemplate,
            ClassificationResult prediction,
            BglDecisionSource originalDecisionSource,
            String matchedTemplatePattern,
            PromptExperiment promptExperiment,
            String promptVersion,
            String rawModelOutput,
            boolean validModelOutput,
            boolean cacheable,
            String validationStatus,
            String validationReason
    ) {
        this.templateKey = templateKey;
        this.normalizedTemplate = normalizedTemplate;
        this.prediction = prediction;
        this.originalDecisionSource = originalDecisionSource;
        this.matchedTemplatePattern = matchedTemplatePattern;
        this.promptExperiment = promptExperiment;
        this.promptVersion = promptVersion;
        this.rawModelOutput = rawModelOutput;
        this.validModelOutput = validModelOutput;
        this.cacheable = cacheable;
        this.validationStatus = validationStatus;
        this.validationReason = validationReason;
        this.createdAt = LocalDateTime.now();
    }

    public void incrementHitCount() {
        hitCount.incrementAndGet();
    }

    public int getHitCount() {
        return hitCount.get();
    }
}
