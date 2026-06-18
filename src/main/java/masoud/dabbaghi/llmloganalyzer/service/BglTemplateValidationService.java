package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Prevents wrong LLM answers from being propagated through the template cache.
 * <p>
 * This service does not change the current prediction.
 * It only decides whether the prediction is safe enough to be cached.
 * <p>
 * Reason:
 * If the LLM misclassifies a frequent template once, the template cache can repeat
 * that mistake for hundreds, thousands, or millions of future log lines.
 */
@Service
public class BglTemplateValidationService {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL;

    /*
     * Strong anomaly signals.
     * These patterns are safe enough to block caching when the LLM predicts NORMAL.
     * Avoid broad keyword-only patterns here.
     */
    private static final Pattern STRONG_ANOMALY_SIGNALS = pattern(
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
     * Strong normal signals.
     * These patterns are safe enough to block caching when the LLM predicts ANOMALY.
     * This list intentionally contains known BGL diagnostic/non-alert patterns that
     * include scary words such as failed, interrupt, uncorrectable, timeout, parity,
     * FATAL, or not accessible.
     */
    private static final Pattern STRONG_NORMAL_SIGNALS = pattern(
            "ciod:\\s+Error\\s+opening\\s+node\\s+map\\s+file\\s+.*No\\s+such\\s+file\\s+or\\s+directory"
                    + "|\\bIdo\\s+packet\\s+timeout\\b"
                    + "|(?:LinkCard|NodeCard).*power\\s+module.*(?:is\\s+)?not\\s+accessible"
                    + "|capture\\s+first\\s+.*uncorrectable\\s+error\\s+address"
                    + "|uncorrectable\\s+error\\s+detected\\s+in\\s+(?:directory|EDRAM|external\\s+DDR|DDR)"
                    + "|memory\\s+manager\\s+uncorrectable\\s+error"
                    + "|^\\s*uncorrectable\\s+error\\b.*(?:<NUM>|\\d+)\\s*$"
                    + "|^\\s*data\\s+storage\\s+interrupt\\s*$"
                    + "|\\bddr:\\s+excessive\\s+soft\\s+failures,\\s+consider\\s+replacing\\s+the\\s+card\\b"
                    + "|(?:^|\\s)(?:instruction\\s+plb\\s+error"
                    + "|data\\s+(?:read|write)\\s+plb\\s+error"
                    + "|tlb\\s+error"
                    + "|i-cache\\s+parity\\s+error"
                    + "|d-cache\\s+(?:search|flush|tag)\\s+parity\\s+error"
                    + "|critical\\s+input\\s+interrupt\\s+enable)\\.*\\s*(?:<NUM>|\\d+)\\b"
                    + "|(?:^|\\s)(?:\\d+|<NUM>):(?:[0-9a-f]{8}|<HEX>|<NUM>)"
                    + "(?:\\s+(?:\\d+|<NUM>):(?:[0-9a-f]{8}|<HEX>|<NUM>)){1,}"
                    + "|ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*(?:No\\s+such\\s+file\\s+or\\s+directory|Permission\\s+denied|Exec\\s+format\\s+error)"
                    + "|ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+(?:No\\s+such\\s+file\\s+or\\s+directory|Permission\\s+denied)"
                    + "|ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*(?:Bad\\s+file\\s+descriptor|Block\\s+device\\s+required|Permission\\s+denied)"
                    + "|program\\s+interrupt:\\s+(?:privileged\\s+instruction|trap\\s+instruction|imprecise\\s+exception|illegal\\s+instruction|unimplemented\\s+operation)"
                    + "|exception\\s+syndrome\\s+register"
                    + "|machine\\s+check:\\s+i-fetch"
                    + "|data\\s+store\\s+interrupt\\s+caused\\s+by\\s+(?:dcbf|icbi)"
                    + "|instruction\\s+address:"
                    + "|data\\s+address:"
                    + "|core\\s+configuration\\s+register:"
                    + "|machine\\s+state\\s+register:"
                    + "|floating\\s+point\\s+status"
                    + "|data\\s+address\\s+space"
                    + "|store\\s+operation"
                    + "|byte\\s+ordering\\s+exception"
                    + "|generating\\s+core"
                    + "|rts\\s+internal\\s+error"
                    + "|ciod:\\s+pollControlDescriptors:\\s+Detected\\s+the\\s+debugger\\s+died"
                    + "|NFS\\s+Mount\\s+failed\\s+.*retrying"
                    + "|rts\\s+tree/torus\\s+link\\s+training\\s+failed"
                    + "|rts:\\s+bad\\s+message\\s+header"
                    + "|ASSERT\\s+condition"
                    + "|Node\\s*card\\s+is\\s+not\\s+fully\\s+functional"
                    + "|NodeCard\\s+is\\s+not\\s+fully\\s+functional"
                    + "|Can\\s+not\\s+get\\s+assembly\\s+information\\s+for\\s+node\\s+card"
                    + "|detected\\s+and\\s+corrected"
                    + "|\\bcorrected\\b"
                    + "|\\bCE\\s+sym\\b"
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
                    "LLM output is not a valid 0/1 classification."
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

        boolean hasStrongAnomalySignal = containsAny(STRONG_ANOMALY_SIGNALS, raw, normalized, combined);
        boolean hasStrongNormalSignal = containsAny(STRONG_NORMAL_SIGNALS, raw, normalized, combined);

        /*
         * If both sides match, the template is too risky for cache.
         * The current prediction is still saved for this single line, but not reused.
         */
        if (hasStrongAnomalySignal && hasStrongNormalSignal) {
            return BglTemplateValidationResult.suspicious(
                    "Template contains both strong normal and strong anomaly signals. Result saved for this line but not cached."
            );
        }

        if (prediction == ClassificationResult.NORMAL && hasStrongAnomalySignal) {
            return BglTemplateValidationResult.suspicious(
                    "LLM predicted NORMAL but the template contains strong anomaly signals. Result saved for this line but not cached."
            );
        }

        if (prediction == ClassificationResult.ANOMALY && hasStrongNormalSignal) {
            return BglTemplateValidationResult.suspicious(
                    "LLM predicted ANOMALY but the template contains strong known-normal BGL signals. Result saved for this line but not cached."
            );
        }

        return BglTemplateValidationResult.approved(
                "No deterministic cache conflict detected."
        );
    }
}
