package masoud.dabbaghi.llmloganalyzer.service;

import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Audits whether one normalized template key occurs with conflicting dataset labels.
 *
 * <p>The hidden label is used only for post-classification audit metadata. Collision
 * status never changes routing, model input, predictions, or cache admission.</p>
 */
@Component
@Slf4j
public class BglTemplateLabelCollisionDetector {

    private final ConcurrentMap<String, ClassificationResult> firstObservedLabels = new ConcurrentHashMap<>();
    private final Set<String> conflictingTemplateKeys = ConcurrentHashMap.newKeySet();

    /** Returns true only when this observation discovers a new conflicting template key. */
    public boolean observe(String templateKey, ClassificationResult datasetLabel) {
        if (templateKey == null || templateKey.isBlank()
                || datasetLabel == null || datasetLabel == ClassificationResult.INVALID) {
            return false;
        }

        ClassificationResult first = firstObservedLabels.putIfAbsent(templateKey, datasetLabel);
        if (first == null || first == datasetLabel) {
            return false;
        }

        boolean newlyDetected = conflictingTemplateKeys.add(templateKey);
        if (newlyDetected) {
            log.warn(
                    "Normalized BGL template has conflicting hidden labels; firstLabel={}, observedLabel={}, templateKey={}. "
                            + "Audit only: classification and cache behavior are unchanged.",
                    first,
                    datasetLabel,
                    templateKey
            );
        }
        return newlyDetected;
    }

    public int conflictCount() {
        return conflictingTemplateKeys.size();
    }

    public int observedTemplateCount() {
        return firstObservedLabels.size();
    }

    /** Starts each run with independent collision-audit state and verifies the reset. */
    public void resetForRun() {
        firstObservedLabels.clear();
        conflictingTemplateKeys.clear();
        if (!firstObservedLabels.isEmpty() || !conflictingTemplateKeys.isEmpty()) {
            throw new IllegalStateException("Template label-collision audit did not reset cleanly");
        }
    }
}
