package masoud.dabbaghi.llmloganalyzer.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BinaryMetricsTest {
    @Test
    void calculatesImbalanceSensitiveMetrics() {
        BinaryMetrics m = BinaryMetrics.from(40, 50, 10, 20);
        assertEquals(.75, m.accuracy(), 1e-12);
        assertEquals(.8, m.precision(), 1e-12);
        assertEquals(2.0 / 3.0, m.recall(), 1e-12);
        assertEquals(5.0 / 6.0, m.specificity(), 1e-12);
        assertEquals(1.0 / 6.0, m.falsePositiveRate(), 1e-12);
        assertEquals(1.0 / 3.0, m.falseNegativeRate(), 1e-12);
        assertEquals((2.0 / 3.0 + 5.0 / 6.0) / 2.0, m.balancedAccuracy(), 1e-12);
        assertEquals((40.0 * 50 - 10.0 * 20) / Math.sqrt(50.0 * 60 * 60 * 70), m.mcc(), 1e-12);
    }

    @Test
    void zeroDenominatorsAreReportedAsZero() {
        BinaryMetrics m = BinaryMetrics.from(0, 0, 0, 0);
        assertEquals(0, m.accuracy());
        assertEquals(0, m.precision());
        assertEquals(0, m.recall());
        assertEquals(0, m.mcc());
    }
}
