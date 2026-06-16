package masoud.dabbaghi.llmloganalyzer.service;

/**
 * Describes the final prompt used in the thesis experiment.
 */
public record PromptSpec(
        PromptExperiment experiment,
        String version,
        String prompt
) {
}
