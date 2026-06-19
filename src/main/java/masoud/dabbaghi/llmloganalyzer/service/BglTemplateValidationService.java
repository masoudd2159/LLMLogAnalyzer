package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/** Prevents one unsafe model decision from being propagated through the template cache. */
@Service
public class BglTemplateValidationService {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL;

    private static final Pattern CLEAR_ANOMALY_SIGNALS = pattern(
            "^\\s*data\\s+storage\\s+interrupt\\s*$"
                    + "|ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Cannot\\s+allocate\\s+memory"
                    + "|MailboxMonitor::serviceMailboxes\\(\\).*socket\\s+closed"
                    + "|^\\s*DDR\\s+machine\\s+check\\s+register:"
                    + "|mmcs_server\\s+exited\\s+normally\\s+with\\s+exit\\s+code\\s+(?:<NON_ZERO>|-[1-9]\\d*|[1-9]\\d*)"
                    + "|\\bdata\\s+TLB\\s+error\\s+interrupt\\b"
                    + "|failed\\s+to\\s+read\\s+message\\s+prefix\\s+on\\s+control\\s+stream"
                    + "|control\\s+stream\\s+closed\\s+unexpectedly"
                    + "|\\bBroken\\s+pipe\\b"
                    + "|kernel\\s+terminated|kernel\\s+panic|rts\\s+panic|\\bpanic:"
                    + "|Lustre\\s+mount\\s+FAILED"
                    + "|Error\\s+receiving\\s+packet\\s+on\\s+tree\\s+network"
                    + "|ciod:.*(?:No\\s+child\\s+processes|Resource\\s+temporarily\\s+unavailable|Device\\s+or\\s+resource\\s+busy|Input/output\\s+error)"
                    + "|\\bmachine\\s+check\\s+interrupt\\b"
                    + "|\\bunrecoverable\\s+(?:system|hardware|memory|storage|network|error|failure)\\b"
                    + "|\\bjob\\s+terminated\\b|\\bnode\\s+crash\\b|\\bnode\\s+failed\\b|\\baborted\\s+by\\s+system\\b"
    );

    private static final Pattern CLEAR_NORMAL_SIGNALS = pattern(
            "^\\s*machine\\s+check:\\s+i-fetch\\.*\\s*(?:0|<ZERO>)\\s*$"
                    + "|^\\s*imprecise\\s+machine\\s+check\\.*\\s*(?:0|<ZERO>)\\s*$"
                    + "|^\\s*data\\s+store\\s+interrupt\\s+caused\\s+by\\s+(?:dcbf|icbi)\\.*\\s*(?:0|<ZERO>)\\s*$"
                    + "|^\\s*rts\\s+internal\\s+error\\s*$"
                    + "|^\\s*Can\\s+not\\s+get\\s+assembly\\s+information\\s+for\\s+node\\s+card\\s*$"
                    + "|^\\s*Node\\s*card\\s+is\\s+not\\s+fully\\s+functional\\s*$"
                    + "|ciod:\\s*pollControlDescriptors:\\s*Detected\\s+the\\s+debugger\\s+died"
                    + "|^\\s*rts\\s+tree/torus\\s+link\\s+training\\s+failed:"
                    + "|^\\s*rts:\\s*bad\\s+message\\s+header:"
                    + "|program\\s+interrupt:\\s*fp\\s+cr\\s+update"
                    + "|program\\s+image\\s+too\\s+big"
                    + "|detected\\s+and\\s+corrected|\\bcorrected\\b|\\bCE\\s+sym\\b"
                    + "|ciod:.*(?:No\\s+such\\s+file\\s+or\\s+directory|Permission\\s+denied|Exec\\s+format\\s+error|Bad\\s+file\\s+descriptor|Block\\s+device\\s+required)"
                    + "|program\\s+interrupt:\\s+(?:privileged\\s+instruction|trap\\s+instruction|imprecise\\s+exception|illegal\\s+instruction|unimplemented\\s+operation)"
                    + "|exception\\s+syndrome\\s+register|instruction\\s+address:|data\\s+address:|core\\s+configuration\\s+register:|machine\\s+state\\s+register:"
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public BglTemplateValidationResult validateForCache(
            String rawMessage,
            String normalizedTemplate,
            ClassificationResult prediction
    ) {
        if (prediction == null || prediction == ClassificationResult.INVALID) {
            return BglTemplateValidationResult.invalid("Output is not a valid 0/1 classification.");
        }

        String raw = safe(rawMessage);
        String normalized = safe(normalizedTemplate);
        if (raw.isBlank() && normalized.isBlank()) {
            return BglTemplateValidationResult.suspicious("Empty BGL message/template; result was not cached.");
        }

        boolean anomalySignal = containsAny(CLEAR_ANOMALY_SIGNALS, raw, normalized);
        boolean normalSignal = containsAny(CLEAR_NORMAL_SIGNALS, raw, normalized);

        if (anomalySignal && normalSignal) {
            return BglTemplateValidationResult.suspicious("Conflicting high-confidence signals; result was not cached.");
        }
        if (prediction == ClassificationResult.NORMAL && anomalySignal) {
            return BglTemplateValidationResult.suspicious("NORMAL conflicts with a high-confidence anomaly signal.");
        }
        if (prediction == ClassificationResult.ANOMALY && normalSignal) {
            return BglTemplateValidationResult.suspicious("ANOMALY conflicts with a high-confidence normal signal.");
        }

        return BglTemplateValidationResult.approved("No high-confidence cache conflict detected.");
    }
}
