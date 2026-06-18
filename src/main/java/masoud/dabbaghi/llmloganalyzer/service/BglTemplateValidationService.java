package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Prevents error propagation in the template cache.
 * <p>
 * A wrong LLM answer for a frequent template can otherwise be reused for thousands of lines.
 * This validator does not change the prediction. It only decides whether the prediction is safe to cache.
 */
@Service
public class BglTemplateValidationService {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Pattern STRONG_ANOMALY_SIGNALS = Pattern.compile(
            "data\\s+TLB\\s+error\\s+interrupt"
                    + "|data\\s+storage\\s+interrupt"
                    + "|failed\\s+to\\s+read\\s+message\\s+prefix\\s+on\\s+control\\s+stream"
                    + "|kernel\\s+terminated"
                    + "|Lustre\\s+mount\\s+FAILED"
                    + "|Error\\s+receiving\\s+packet\\s+on\\s+tree\\s+network"
                    + "|No\\s+child\\s+processes"
                    + "|ciod:\\s+LOGIN\\s+chdir\\(.*\\)\\s+failed:\\s+Input/output\\s+error"
                    + "|uncorrected|uncorrectable|unrecoverable",
            FLAGS
    );

    private static final Pattern STRONG_NORMAL_SIGNALS = Pattern.compile(
            "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image"
                    + "|ciod:\\s+LOGIN\\s+chdir\\(.*\\)\\s+failed:\\s+No\\s+such\\s+file\\s+or\\s+directory"
                    + "|program\\s+interrupt:\\s+(privileged\\s+instruction|trap\\s+instruction|imprecise\\s+exception|illegal\\s+instruction|unimplemented\\s+operation)"
                    + "|exception\\s+syndrome\\s+register"
                    + "|machine\\s+check:\\s+i-fetch"
                    + "|data\\s+store\\s+interrupt\\s+caused\\s+by\\s+(dcbf|icbi)"
                    + "|rts:\\s+bad\\s+message\\s+header"
                    + "|rts\\s+tree/torus\\s+link\\s+training\\s+failed"
                    + "|detected\\s+and\\s+corrected|\\bcorrected\\b"
                    + "|Node\\s*card\\s+is\\s+not\\s+fully\\s+functional"
                    + "|Can\\s+not\\s+get\\s+assembly\\s+information\\s+for\\s+node\\s+card",
            FLAGS
    );

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public BglTemplateValidationResult validateForCache(
            String rawMessage,
            String normalizedTemplate,
            ClassificationResult prediction
    ) {
        if (prediction == null || prediction == ClassificationResult.INVALID) {
            return BglTemplateValidationResult.invalid("LLM output is not a valid 0/1 classification.");
        }

        String combined = (safe(rawMessage) + " " + safe(normalizedTemplate)).trim();

        if (prediction == ClassificationResult.NORMAL
                && STRONG_ANOMALY_SIGNALS.matcher(combined).find()) {
            return BglTemplateValidationResult.suspicious(
                    "LLM predicted NORMAL but the template contains strong anomaly signals. Result saved for this line but not cached."
            );
        }

        if (prediction == ClassificationResult.ANOMALY
                && STRONG_NORMAL_SIGNALS.matcher(combined).find()) {
            return BglTemplateValidationResult.suspicious(
                    "LLM predicted ANOMALY but the template contains strong known-normal BGL signals. Result saved for this line but not cached."
            );
        }

        return BglTemplateValidationResult.approved("No deterministic conflict detected.");
    }
}
