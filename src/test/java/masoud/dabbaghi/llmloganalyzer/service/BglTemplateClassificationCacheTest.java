package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.BglDecisionSource;
import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglTemplateClassificationCacheTest {

    @Test
    void resetForRunRemovesAndVerifiesStateFromPreviousRun() {
        BglTemplateClassificationCache cache = new BglTemplateClassificationCache();
        cache.putIfCacheable(new BglCachedClassification(
                "key",
                "template",
                ClassificationResult.NORMAL,
                BglDecisionSource.LLM,
                null,
                PromptExperiment.TEMPLATE_AWARE_FINAL,
                "v-test",
                "{\"prediction\":\"normal\",\"confidence\":0.9,\"reason\":\"Routine event.\",\"category\":\"diagnostic\"}",
                true,
                true,
                "APPROVED",
                "test"
        ));

        assertEquals(1, cache.size());
        cache.resetForRun();

        assertEquals(0, cache.size());
        assertTrue(cache.find("key").isEmpty());
    }
}
