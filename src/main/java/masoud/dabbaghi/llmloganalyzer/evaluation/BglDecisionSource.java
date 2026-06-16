package masoud.dabbaghi.llmloganalyzer.evaluation;

/**
 * Shows how the final prediction was produced.
 * <p>
 * TEMPLATE_GUARD:
 * A deterministic BGL template rule classified the message before the LLM call.
 * <p>
 * LLM:
 * No deterministic template matched, so the log entry was sent to the LLM.
 */
public enum BglDecisionSource {
    TEMPLATE_GUARD,
    LLM
}
