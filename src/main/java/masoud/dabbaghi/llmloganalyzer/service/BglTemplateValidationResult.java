package masoud.dabbaghi.llmloganalyzer.service;

/**
 * Validation output for template-level LLM predictions.
 * <p>
 * approved=true means the result can be cached.
 * approved=false means the line can still be saved, but the result should not be reused forever.
 */
public record BglTemplateValidationResult(
        boolean approved,
        String status,
        String reason
) {
    public static BglTemplateValidationResult approved(String reason) {
        return new BglTemplateValidationResult(true, "APPROVED", reason);
    }

    public static BglTemplateValidationResult suspicious(String reason) {
        return new BglTemplateValidationResult(false, "SUSPICIOUS_NOT_CACHED", reason);
    }

    public static BglTemplateValidationResult invalid(String reason) {
        return new BglTemplateValidationResult(false, "INVALID_NOT_CACHED", reason);
    }
}
