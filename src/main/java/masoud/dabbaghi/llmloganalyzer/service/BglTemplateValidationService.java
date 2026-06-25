package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prevents uncertain or conflicting BGL predictions from being propagated through
 * the template cache.
 */
@Service
public class BglTemplateValidationService {

    /**
     * A previously unseen template from one of these families must not be cached after
     * a single LLM decision. Known members are still cacheable when the guard recognizes
     * them and agrees with the prediction.
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

        String normalized = value.toLowerCase(Locale.ROOT);
        for (String term : CACHE_SENSITIVE_TERMS) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public BglTemplateValidationResult validateForCache(
            String rawMessage,
            String normalizedTemplate,
            ClassificationResult prediction
    ) {
        if (prediction == null || prediction == ClassificationResult.INVALID) {
            return BglTemplateValidationResult.invalid(
                    "Output is not a valid 0/1 classification."
            );
        }

        if (isBlank(rawMessage) && isBlank(normalizedTemplate)) {
            return BglTemplateValidationResult.suspicious(
                    "Empty BGL message/template; result was not cached."
            );
        }

        Optional<BglTemplateGuard.GuardResult> deterministic =
                BglTemplateGuard.classify(rawMessage, normalizedTemplate);

        if (deterministic.isPresent()) {
            BglTemplateGuard.GuardResult guardResult = deterministic.get();

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
         * Do not let one unsupported machine-check/parity prediction become a permanent
         * template-cache decision. The prediction can still be returned and evaluated,
         * but it will be checked again on the next occurrence.
         */
        if (isCacheSensitive(rawMessage) || isCacheSensitive(normalizedTemplate)) {
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
