package masoud.dabbaghi.llmloganalyzer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptGeneratorTest {

    @Test
    void promptOnlyContainsGeneralInstructionsWithoutRuleGuardKnowledge() {
        PromptSpec prompt = PromptGenerator.finalBglPrompt(false);

        assertEquals(PromptExperiment.TEMPLATE_AWARE_PROMPT_ONLY, prompt.experiment());
        assertTrue(prompt.prompt().contains("GENERAL CLASSIFICATION INSTRUCTIONS"));
        assertFalse(prompt.prompt().contains("PROMPT-ONLY GUARD RULES"));
        assertFalse(prompt.prompt().contains("data storage interrupt"));
        assertFalse(prompt.prompt().contains("No child processes"));
        assertFalse(prompt.prompt().contains("=>"));
    }

    @Test
    void hybridFallbackRetainsExistingBglDomainInstructions() {
        PromptSpec prompt = PromptGenerator.finalBglPrompt(true);

        assertEquals(PromptExperiment.TEMPLATE_AWARE_FINAL, prompt.experiment());
        assertTrue(prompt.prompt().contains("ALWAYS PREDICT NORMAL"));
        assertTrue(prompt.prompt().contains("PREDICT ANOMALY FOR EXPLICIT SYSTEM FAILURE"));
    }

    @Test
    void exposesExactlyTwoThesisExperimentsAndNoLegacyOutputInstruction() {
        assertEquals(2, PromptGenerator.bglPromptExperiments().size());
        for (PromptSpec prompt : PromptGenerator.bglPromptExperiments()) {
            assertTrue(prompt.prompt().contains("\"prediction\":\"normal|anomaly\""));
            assertFalse(prompt.prompt().matches("(?is).*return\\s+[01]\\b.*"));
            assertFalse(prompt.prompt().contains("\"label\""));
        }
    }
}
