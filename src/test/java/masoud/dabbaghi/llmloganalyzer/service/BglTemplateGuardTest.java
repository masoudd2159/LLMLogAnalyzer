package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class BglTemplateGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "machine check status register: 0x81000000",
            "machine check status register: <HEX>",
            "machine check enable..............1",
            "machine check enable..............<NUM>",
            "i-cache parity error..............0",
            "i-cache parity error..............<NUM>",
            "imprecise machine check...........1",
            "imprecise machine check...........<NON_ZERO>",
            "capture first EDRAM parity error address..0",
            "capture first EDRAM parity error address..<NUM>"
    })
    void shouldClassifyKnownDiagnosticFieldsAsNormal(String message) {
        BglTemplateGuard.GuardResult result = BglTemplateGuard.classify(message).orElseThrow();

        assertEquals(ClassificationResult.NORMAL, result.prediction());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "machine check interrupt",
            "data storage interrupt",
            "data TLB error interrupt",
            "DDR machine check register: 0x1234",
            "kernel panic"
    })
    void shouldPreserveExplicitAnomalies(String message) {
        BglTemplateGuard.GuardResult result = BglTemplateGuard.classify(message).orElseThrow();

        assertEquals(ClassificationResult.ANOMALY, result.prediction());
    }

    @Test
    void shouldApproveCacheWhenPredictionAgreesWithKnownRule() {
        BglTemplateValidationService service = new BglTemplateValidationService();

        BglTemplateValidationResult result = service.validateForCache(
                "machine check enable..............1",
                "machine check enable..............<NUM>",
                ClassificationResult.NORMAL
        );

        assertTrue(result.approved());
        assertEquals("APPROVED", result.status());
    }

    @Test
    void shouldRejectCacheWhenPredictionConflictsWithKnownRule() {
        BglTemplateValidationService service = new BglTemplateValidationService();

        BglTemplateValidationResult result = service.validateForCache(
                "machine check status register: 0x81000000",
                "machine check status register: <HEX>",
                ClassificationResult.ANOMALY
        );

        assertFalse(result.approved());
        assertEquals("SUSPICIOUS_NOT_CACHED", result.status());
    }

    @Test
    void shouldNotCacheUnknownSensitiveTemplate() {
        BglTemplateValidationService service = new BglTemplateValidationService();

        BglTemplateValidationResult result = service.validateForCache(
                "unknown L2 parity error diagnostic state abc",
                "unknown l2 parity error diagnostic state <HEX>",
                ClassificationResult.ANOMALY
        );

        assertFalse(result.approved());
        assertEquals("SUSPICIOUS_NOT_CACHED", result.status());
    }
}
