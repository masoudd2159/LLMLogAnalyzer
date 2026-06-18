package masoud.dabbaghi.llmloganalyzer.evaluation;

/**
 * Shows how the final prediction was produced.
 * <p>
 * TEMPLATE_GUARD:
 * A deterministic BGL template rule classified the message before the LLM call.
 * <p>
 * LLM:
 * No deterministic template matched, so the template was sent to the LLM.
 * <p>
 * TEMPLATE_CACHE:
 * The normalized template was already classified before, so the saved template-level
 * prediction was reused without another LLM call.
 */
public enum BglDecisionSource {
    TEMPLATE_GUARD,
    LLM,
    TEMPLATE_CACHE
}
