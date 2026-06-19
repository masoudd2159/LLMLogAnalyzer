package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Prevents a deterministic BGL conflict from being propagated through the template cache. */
@Service
public class BglTemplateValidationService {

    public BglTemplateValidationResult validateForCache(
            String rawMessage,
            String normalizedTemplate,
            ClassificationResult prediction
    ) {
        if (prediction == null || prediction == ClassificationResult.INVALID) {
            return BglTemplateValidationResult.invalid("Output is not a valid 0/1 classification.");
        }

        if (isBlank(rawMessage) && isBlank(normalizedTemplate)) {
            return BglTemplateValidationResult.suspicious(
                    "Empty BGL message/template; result was not cached."
            );
        }

        Optional<BglTemplateGuard.GuardResult> deterministic =
                BglTemplateGuard.classify(rawMessage, normalizedTemplate);

        if (deterministic.isPresent() && deterministic.get().prediction() != prediction) {
            return BglTemplateValidationResult.suspicious(
                    "LLM prediction conflicts with deterministic rule: "
                            + deterministic.get().matchedTemplatePattern()
            );
        }

        return BglTemplateValidationResult.approved(
                deterministic
                        .map(result -> "Prediction agrees with deterministic rule: "
                                + result.matchedTemplatePattern())
                        .orElse("No high-confidence cache conflict detected.")
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
