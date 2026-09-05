package masoud.dabbaghi.llmloganalyzer.service;

/** Validated semantic response returned by the model, or an auditable invalid result. */
public record ModelClassificationResponse(
        String prediction,
        double confidence,
        String reason,
        String category,
        String rawOutput,
        boolean valid,
        String validationError,
        int promptTokenCount,
        int outputTokenCount,
        long totalDurationNanos,
        long loadDurationNanos
) {
    public static ModelClassificationResponse valid(
            String prediction,
            double confidence,
            String reason,
            String category,
            String rawOutput
    ) {
        return new ModelClassificationResponse(
                prediction, confidence, reason, category, rawOutput, true, null, 0, 0, 0, 0
        );
    }

    public static ModelClassificationResponse invalid(String rawOutput, String validationError) {
        return new ModelClassificationResponse(
                "invalid", Double.NaN, null, null, rawOutput, false, validationError, 0, 0, 0, 0
        );
    }

    public ModelClassificationResponse withUsage(
            int promptTokens,
            int outputTokens,
            long totalDuration,
            long loadDuration
    ) {
        return new ModelClassificationResponse(
                prediction,
                confidence,
                reason,
                category,
                rawOutput,
                valid,
                validationError,
                promptTokens,
                outputTokens,
                totalDuration,
                loadDuration
        );
    }
}
