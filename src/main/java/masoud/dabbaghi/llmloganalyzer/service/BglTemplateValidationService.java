package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Prevents unsafe template-level cache propagation.
 * <p>
 * This service does not change the current prediction.
 * It only decides whether the prediction is safe enough to be reused by the cache.
 * <p>
 * Reason:
 * If the LLM or guard misclassifies a frequent template once, the template cache can repeat
 * that mistake for hundreds, thousands, or millions of future log lines.
 */
@Service
public class BglTemplateValidationService {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL;

    /*
     * Very clear anomaly signals.
     * If a prediction says NORMAL while these are present, the result is too risky to cache.
     */
    private static final Pattern CLEAR_ANOMALY_SIGNALS = pattern(
            "\\bdata\\s+TLB\\s+error\\s+interrupt\\b"
                    + "|(?:ciod:\\s*)?failed\\s+to\\s+read\\s+message\\s+prefix\\s+on\\s+control\\s+stream"
                    + "|control\\s+stream\\s+closed\\s+unexpectedly"
                    + "|\\bBroken\\s+pipe\\b"
                    + "|(?:rts:\\s*)?kernel\\s+terminated"
                    + "|\\bkernel\\s+panic\\b"
                    + "|\\brts\\s+panic\\b"
                    + "|\\bpanic:"
                    + "|\\bLustre\\s+mount\\s+FAILED\\b"
                    + "|Error\\s+receiving\\s+packet\\s+on\\s+tree\\s+network"
                    + "|ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*No\\s+child\\s+processes"
                    + "|ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Resource\\s+temporarily\\s+unavailable"
                    + "|ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Device\\s+or\\s+resource\\s+busy"
                    + "|ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+Input/output\\s+error"
                    + "|ciod:\\s+Error\\s+loading\\s+.*Input/output\\s+error"
                    + "|^\\s*machine\\s+check\\s+interrupt\\s*$"
                    + "|\\bunrecoverable\\s+(?:system|hardware|memory|storage|network|error|failure)\\b"
                    + "|\\bfatal\\s+hardware\\b"
                    + "|\\bhardware\\s+failure\\b"
                    + "|\\bpower\\s+failure\\b"
                    + "|\\bfan\\s+failure\\b"
                    + "|\\bthermal\\s+failure\\b"
                    + "|\\btemperature\\s+critical\\b"
                    + "|\\bjob\\s+terminated\\b"
                    + "|\\bnode\\s+crash\\b"
                    + "|\\bnode\\s+failed\\b"
                    + "|\\baborted\\s+by\\s+system\\b"
    );

    /*
     * Very clear normal signals.
     * Broad diagnostic words such as uncorrectable, interrupt, parity, timeout,
     * not accessible, and bad message header are intentionally not here because
     * they can increase false negatives or block useful anomaly cache entries.
     */
    private static final Pattern CLEAR_NORMAL_SIGNALS = pattern(
            "detected\\s+and\\s+corrected"
                    + "|\\bcorrected\\b"
                    + "|\\bCE\\s+sym\\b"
                    + "|ciod:\\s+Error\\s+opening\\s+node\\s+map\\s+file\\s+.*No\\s+such\\s+file\\s+or\\s+directory"
                    + "|ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*(?:No\\s+such\\s+file\\s+or\\s+directory|Permission\\s+denied|Exec\\s+format\\s+error)"
                    + "|ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+(?:No\\s+such\\s+file\\s+or\\s+directory|Permission\\s+denied)"
                    + "|ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*(?:Bad\\s+file\\s+descriptor|Block\\s+device\\s+required|Permission\\s+denied)"
                    + "|program\\s+interrupt:\\s+(?:privileged\\s+instruction|trap\\s+instruction|imprecise\\s+exception|illegal\\s+instruction|unimplemented\\s+operation)"
                    + "|exception\\s+syndrome\\s+register"
                    + "|instruction\\s+address:"
                    + "|data\\s+address:"
                    + "|core\\s+configuration\\s+register:"
                    + "|machine\\s+state\\s+register:"
                    + "|floating\\s+point\\s+status"
                    + "|data\\s+address\\s+space"
                    + "|store\\s+operation"
                    + "|byte\\s+ordering\\s+exception"
                    + "|(?:^|\\s)(?:\\d+|<NUM>):(?:[0-9a-f]{8}|<HEX>|<NUM>)"
                    + "(?:\\s+(?:\\d+|<NUM>):(?:[0-9a-f]{8}|<HEX>|<NUM>)){1,}"
                    + "|NFS\\s+Mount\\s+failed\\s+.*retrying"
    );

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, FLAGS);
    }

    private static boolean contains(Pattern pattern, String value) {
        return value != null && !value.isBlank() && pattern.matcher(value).find();
    }

    private static boolean containsAny(Pattern pattern, String... values) {
        if (values == null) {
            return false;
        }

        for (String value : values) {
            if (contains(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private static String combine(String rawMessage, String normalizedTemplate) {
        return (safe(rawMessage) + " " + safe(normalizedTemplate)).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public BglTemplateValidationResult validateForCache(
            String rawMessage,
            String normalizedTemplate,
            ClassificationResult prediction
    ) {
        if (prediction == null || prediction == ClassificationResult.INVALID) {
            return BglTemplateValidationResult.invalid(
                    "Output is not a valid 0/1 classification."
            );
        }

        String raw = safe(rawMessage);
        String normalized = safe(normalizedTemplate);
        String combined = combine(raw, normalized);

        if (combined.isBlank()) {
            return BglTemplateValidationResult.suspicious(
                    "Empty BGL message/template. Result saved for this line but not cached."
            );
        }

        boolean hasClearAnomalySignal = containsAny(CLEAR_ANOMALY_SIGNALS, raw, normalized, combined);
        boolean hasClearNormalSignal = containsAny(CLEAR_NORMAL_SIGNALS, raw, normalized, combined);

        if (hasClearAnomalySignal && hasClearNormalSignal) {
            return BglTemplateValidationResult.suspicious(
                    "Template contains both clear normal and clear anomaly signals. Result saved for this line but not cached."
            );
        }

        if (prediction == ClassificationResult.NORMAL && hasClearAnomalySignal) {
            return BglTemplateValidationResult.suspicious(
                    "Prediction is NORMAL but the template contains clear anomaly signals. Result saved for this line but not cached."
            );
        }

        if (prediction == ClassificationResult.ANOMALY && hasClearNormalSignal) {
            return BglTemplateValidationResult.suspicious(
                    "Prediction is ANOMALY but the template contains clear known-normal BGL signals. Result saved for this line but not cached."
            );
        }

        return BglTemplateValidationResult.approved(
                "No deterministic cache conflict detected."
        );
    }
}
