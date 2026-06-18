package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BglTemplateValidationServiceTest {

    private final BglTemplateValidationService service = new BglTemplateValidationService();

    @Test
    void normalPredictionWithStrongAnomalySignalIsNotCached() {
        BglTemplateValidationResult result = service.validateForCache(
                "data TLB error interrupt",
                "data TLB error interrupt",
                ClassificationResult.NORMAL
        );

        assertFalse(result.approved());
        assertEquals("SUSPICIOUS_NOT_CACHED", result.status());
    }

    @Test
    void anomalyPredictionWithKnownNormalCorrectedSignalIsNotCached() {
        BglTemplateValidationResult result = service.validateForCache(
                "ddr errors detected and corrected",
                "ddr errors detected and corrected",
                ClassificationResult.ANOMALY
        );

        assertFalse(result.approved());
        assertEquals("SUSPICIOUS_NOT_CACHED", result.status());
    }

    @Test
    void validNonConflictingPredictionCanBeCached() {
        BglTemplateValidationResult result = service.validateForCache(
                "control stream closed unexpectedly",
                "control stream closed unexpectedly",
                ClassificationResult.ANOMALY
        );

        assertTrue(result.approved());
    }
}
