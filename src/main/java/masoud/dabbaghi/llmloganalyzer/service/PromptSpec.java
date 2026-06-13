package masoud.dabbaghi.llmloganalyzer.service;

/**
 * Describes the final prompt used for a thesis experiment run.
 */
public record PromptSpec(
        PromptExperiment experiment,
        String version,
        String prompt
) {
}