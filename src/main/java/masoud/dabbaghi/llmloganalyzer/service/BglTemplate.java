package masoud.dabbaghi.llmloganalyzer.service;

/**
 * Represents a normalized BGL log template.
 * <p>
 * The key is used for exact cache lookup. The modelInput is the label-free
 * prompt payload sent to the LLM when this template has not been classified yet.
 */
public record BglTemplate(
        String templateKey,
        String normalizedMessage,
        String modelInput
) {
}
