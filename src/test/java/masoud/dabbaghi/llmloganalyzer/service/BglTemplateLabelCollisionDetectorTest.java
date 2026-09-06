package masoud.dabbaghi.llmloganalyzer.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglTemplateLabelCollisionDetectorTest {

    @Test
    void detectsAndLogsConflictingLabelsWithoutOverwritingTheFirstObservation() {
        Logger logger = (Logger) LoggerFactory.getLogger(BglTemplateLabelCollisionDetector.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            BglTemplateLabelCollisionDetector detector = new BglTemplateLabelCollisionDetector();

            assertFalse(detector.observe("template-a", ClassificationResult.NORMAL));
            assertFalse(detector.observe("template-a", ClassificationResult.NORMAL));
            assertTrue(detector.observe("template-a", ClassificationResult.ANOMALY));
            assertFalse(detector.observe("template-a", ClassificationResult.ANOMALY));
            assertEquals(1, detector.observedTemplateCount());
            assertEquals(1, detector.conflictCount());
            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getFormattedMessage().contains("conflicting hidden labels")));

            detector.resetForRun();
            assertEquals(0, detector.observedTemplateCount());
            assertEquals(0, detector.conflictCount());
        } finally {
            logger.detachAppender(appender);
        }
    }
}
