package masoud.dabbaghi.llmloganalyzer.service;

/**
 * Final thesis experiment type.
 * <p>
 * The final thesis experiment uses one prompt only.
 * The comparison target is the selected baseline paper, not other prompt variants.
 */
public enum PromptExperiment {
    TEMPLATE_AWARE_FINAL,
    TEMPLATE_AWARE_GUARD_RULES_EMBEDDED
}
