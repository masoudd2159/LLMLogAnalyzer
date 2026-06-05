package masoud.dabbaghi.llmloganalyzer.service;

public record PromptSpec(
        PromptExperiment experiment,
        String version,
        String prompt
) {
}