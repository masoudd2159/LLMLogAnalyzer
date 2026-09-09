package masoud.dabbaghi.llmloganalyzer.visualization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationChartServiceTest {
    @Test
    void chartTitlesAreDerivedFromStoredClassificationMode() {
        assertEquals("Hybrid Rule Guard + LLM", EvaluationChartService.displayNameForMode("HYBRID_GUARD_AND_LLM"));
        assertEquals("Prompt-only LLM", EvaluationChartService.displayNameForMode("PROMPT_ONLY_LLM"));
        assertEquals("BGL Experiment", EvaluationChartService.displayNameForMode(null));
    }
}
