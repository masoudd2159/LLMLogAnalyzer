package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Controls whether an LLM prediction is safe to store in the template cache.
 * <p>
 * Hybrid mode:
 * Uses BglTemplateGuard to validate the LLM prediction before caching.
 * <p>
 * Prompt-only mode:
 * Does not use BglTemplateGuard or deterministic Guard rules.
 */
@Service
public class BglTemplateValidationService {

    /**
     * Templates from these families should not be cached after one unsupported
     * LLM prediction while deterministic Guard validation is enabled.
     */
    private static final List<String> CACHE_SENSITIVE_TERMS = List.of(
            "machine check",
            "parity error",
            "uncorrectable error",
            "status register",
            "capture first"
    );

    private static boolean isCacheSensitive(String value) {
        if (isBlank(value)) {
            return false;
        }

        String normalizedValue =
                value.toLowerCase(Locale.ROOT);

        return CACHE_SENSITIVE_TERMS.stream()
                .anyMatch(normalizedValue::contains);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Backward-compatible method.
     * <p>
     * Existing tests or older callers using three parameters will continue
     * to run in Guard-enabled validation mode.
     */
    public BglTemplateValidationResult validateForCache(
            String rawMessage,
            String normalizedTemplate,
            ClassificationResult prediction
    ) {
        return validateForCache(
                rawMessage,
                normalizedTemplate,
                prediction,
                true
        );
    }

    /**
     * Validates whether an LLM prediction may enter the template cache.
     *
     * @param rawMessage         original BGL message
     * @param normalizedTemplate normalized template
     * @param prediction         LLM classification result
     * @param useTemplateGuard   true for hybrid mode, false for prompt-only mode
     */
    public BglTemplateValidationResult validateForCache(
            String rawMessage,
            String normalizedTemplate,
            ClassificationResult prediction,
            boolean useTemplateGuard
    ) {
        if (prediction == null || prediction == ClassificationResult.INVALID) {
            return BglTemplateValidationResult.invalid(
                    "Output is not a valid normal/anomaly classification."
            );
        }

        if (isBlank(rawMessage) && isBlank(normalizedTemplate)) {
            return BglTemplateValidationResult.suspicious(
                    "Empty BGL message/template; result was not cached."
            );
        }

        /*
         * Prompt-only experiment:
         *
         * The deterministic BglTemplateGuard is completely disabled.
         * Therefore, valid LLM predictions are approved without comparing
         * them against Guard rules.
         */
        if (!useTemplateGuard) {
            return BglTemplateValidationResult.approved(
                    "Template Guard is disabled; valid prompt-only LLM prediction approved."
            );
        }

        /*
         * Hybrid experiment:
         *
         * Compare the LLM prediction with deterministic Guard knowledge.
         */
        Optional<BglTemplateGuard.GuardResult> deterministicResult =
                BglTemplateGuard.classify(
                        rawMessage,
                        normalizedTemplate
                );

        if (deterministicResult.isPresent()) {
            BglTemplateGuard.GuardResult guardResult =
                    deterministicResult.get();

            if (guardResult.prediction() != prediction) {
                return BglTemplateValidationResult.suspicious(
                        "LLM prediction conflicts with deterministic rule: "
                                + guardResult.matchedTemplatePattern()
                );
            }

            return BglTemplateValidationResult.approved(
                    "Prediction agrees with deterministic rule: "
                            + guardResult.matchedTemplatePattern()
            );
        }

        /*
         * Do not propagate one unsupported LLM prediction across a sensitive
         * machine-check or parity template.
         *
         * The current prediction is still evaluated and stored, but it is not
         * inserted into the template cache.
         */
        if (isCacheSensitive(rawMessage)
                || isCacheSensitive(normalizedTemplate)) {

            return BglTemplateValidationResult.suspicious(
                    "Cache-sensitive BGL template has no deterministic rule; "
                            + "single LLM prediction was not cached."
            );
        }

        return BglTemplateValidationResult.approved(
                "No deterministic conflict or cache-sensitive pattern detected."
        );
    }
}
