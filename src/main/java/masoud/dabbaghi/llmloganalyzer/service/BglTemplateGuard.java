package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lightweight deterministic BGL template guard.
 * <p>
 * This class is only a safe shortcut before the LLM.
 * It must classify only very obvious BGL templates. Anything ambiguous, keyword-only,
 * or dataset-dependent must return Optional.empty() and be sent to the LLM.
 * <p>
 * Important design rule:
 * Do not add broad keyword rules here. Words such as failed, fatal, interrupt,
 * uncorrectable, unavailable, timeout, parity, or not accessible are not enough by themselves.
 */
public final class BglTemplateGuard {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL;

    /*
     * Very clear normal/non-alert patterns.
     * These are safe shortcuts because they describe diagnostics, corrected errors,
     * user/environment issues, or retryable/non-terminal messages.
     */
    private static final List<TemplateRule> CLEAR_NORMAL_RULES = List.of(
            rule("NORMAL_CORRECTED_ERROR",
                    "detected\\s+and\\s+corrected|\\bcorrected\\b|\\bCE\\s+sym\\b"),

            rule("NORMAL_CIOD_ERROR_OPENING_NODE_MAP_NO_SUCH_FILE",
                    "ciod:\\s+Error\\s+opening\\s+node\\s+map\\s+file\\s+.*No\\s+such\\s+file\\s+or\\s+directory"),

            rule("NORMAL_CIOD_ERROR_LOADING_NO_SUCH_FILE",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*No\\s+such\\s+file\\s+or\\s+directory"),

            rule("NORMAL_CIOD_ERROR_LOADING_PERMISSION_DENIED",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*Permission\\s+denied"),

            rule("NORMAL_CIOD_ERROR_LOADING_EXEC_FORMAT",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*Exec\\s+format\\s+error"),

            rule("NORMAL_CIOD_LOGIN_CHDIR_NO_SUCH_FILE",
                    "ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+No\\s+such\\s+file\\s+or\\s+directory"),

            rule("NORMAL_CIOD_LOGIN_CHDIR_PERMISSION_DENIED",
                    "ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+Permission\\s+denied"),

            rule("NORMAL_CIOD_NODE_MAP_PERMISSION_OR_DESCRIPTOR",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*(?:Bad\\s+file\\s+descriptor|Block\\s+device\\s+required|Permission\\s+denied)"),

            rule("NORMAL_PROGRAM_INTERRUPT_USER_CODE",
                    "program\\s+interrupt:\\s+(?:privileged\\s+instruction|trap\\s+instruction|imprecise\\s+exception|illegal\\s+instruction|unimplemented\\s+operation)"),

            rule("NORMAL_REGISTER_OR_CONTEXT_DIAGNOSTIC",
                    "exception\\s+syndrome\\s+register:|instruction\\s+address:|data\\s+address:|core\\s+configuration\\s+register:|machine\\s+state\\s+register:|floating\\s+point\\s+status|data\\s+address\\s+space|store\\s+operation|byte\\s+ordering\\s+exception"),

            rule("NORMAL_HEX_REGISTER_DUMP_LINE",
                    "(?:^|\\s)(?:\\d+|<NUM>):(?:[0-9a-f]{8}|<HEX>|<NUM>)"
                            + "(?:\\s+(?:\\d+|<NUM>):(?:[0-9a-f]{8}|<HEX>|<NUM>)){1,}"),

            rule("NORMAL_NFS_MOUNT_RETRYING",
                    "NFS\\s+Mount\\s+failed\\s+.*retrying")
    );

    /*
     * Very clear anomaly/alert patterns.
     * These indicate execution failure, communication break, I/O/mount failure,
     * kernel/runtime termination, or node/job-impacting behavior.
     */
    private static final List<TemplateRule> CLEAR_ANOMALY_RULES = List.of(
            rule("ANOMALY_DATA_TLB_ERROR_INTERRUPT",
                    "\\bdata\\s+TLB\\s+error\\s+interrupt\\b"),

            rule("ANOMALY_CONTROL_STREAM_PREFIX_READ_FAILED",
                    "(?:ciod:\\s*)?failed\\s+to\\s+read\\s+message\\s+prefix\\s+on\\s+control\\s+stream"),

            rule("ANOMALY_CONTROL_STREAM_CLOSED",
                    "control\\s+stream\\s+closed\\s+unexpectedly|\\bBroken\\s+pipe\\b"),

            rule("ANOMALY_KERNEL_TERMINATED",
                    "(?:rts:\\s*)?kernel\\s+terminated"),

            rule("ANOMALY_KERNEL_OR_RTS_PANIC",
                    "\\bkernel\\s+panic\\b|\\brts\\s+panic\\b|\\bpanic:"),

            rule("ANOMALY_LUSTRE_MOUNT_FAILED",
                    "\\bLustre\\s+mount\\s+FAILED\\b"),

            rule("ANOMALY_TREE_NETWORK_PACKET_RECEIVE_ERROR",
                    "Error\\s+receiving\\s+packet\\s+on\\s+tree\\s+network"),

            rule("ANOMALY_CIOD_NODE_MAP_NO_CHILD_PROCESSES",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*No\\s+child\\s+processes"),

            rule("ANOMALY_CIOD_NODE_MAP_RESOURCE_TEMPORARILY_UNAVAILABLE",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Resource\\s+temporarily\\s+unavailable"),

            rule("ANOMALY_CIOD_NODE_MAP_DEVICE_OR_RESOURCE_BUSY",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Device\\s+or\\s+resource\\s+busy"),

            rule("ANOMALY_CIOD_LOGIN_CHDIR_IO_ERROR",
                    "ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+Input/output\\s+error"),

            rule("ANOMALY_CIOD_LOADING_IO_ERROR",
                    "ciod:\\s+Error\\s+loading\\s+.*Input/output\\s+error"),

            rule("ANOMALY_MACHINE_CHECK_INTERRUPT",
                    "^\\s*machine\\s+check\\s+interrupt\\s*$|\\bmachine\\s+check\\s+interrupt\\b"),

            rule("ANOMALY_UNRECOVERABLE_SYSTEM_FAILURE",
                    "\\bunrecoverable\\s+(?:system|hardware|memory|storage|network|error|failure)\\b"),

            rule("ANOMALY_FATAL_HARDWARE_FAILURE",
                    "\\bfatal\\s+hardware\\b|\\bhardware\\s+failure\\b|\\bpower\\s+failure\\b|\\bfan\\s+failure\\b|\\bthermal\\s+failure\\b|\\btemperature\\s+critical\\b"),

            rule("ANOMALY_JOB_OR_NODE_TERMINATED",
                    "\\bjob\\s+terminated\\b|\\bnode\\s+crash\\b|\\bnode\\s+failed\\b|\\baborted\\s+by\\s+system\\b")
    );

    private BglTemplateGuard() {
        /* Utility class. */
    }

    public static Optional<GuardResult> classify(String message) {
        return classify(message, null);
    }

    public static Optional<GuardResult> classify(String rawMessage, String normalizedTemplate) {
        String raw = safe(rawMessage);
        String normalized = safe(normalizedTemplate);

        if (raw.isBlank() && normalized.isBlank()) {
            return Optional.empty();
        }

        Optional<GuardResult> anomaly = firstMatch(
                CLEAR_ANOMALY_RULES,
                ClassificationResult.ANOMALY,
                raw,
                normalized
        );
        if (anomaly.isPresent()) {
            return anomaly;
        }

        return firstMatch(
                CLEAR_NORMAL_RULES,
                ClassificationResult.NORMAL,
                raw,
                normalized
        );
    }

    private static Optional<GuardResult> firstMatch(
            List<TemplateRule> rules,
            ClassificationResult prediction,
            String rawMessage,
            String normalizedTemplate
    ) {
        for (TemplateRule rule : rules) {
            if (rule.matches(rawMessage) || rule.matches(normalizedTemplate)) {
                return Optional.of(new GuardResult(prediction, rule.name()));
            }
        }
        return Optional.empty();
    }

    private static TemplateRule rule(String name, String regex) {
        return new TemplateRule(name, Pattern.compile(regex, FLAGS));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record TemplateRule(String name, Pattern pattern) {
        private boolean matches(String value) {
            return value != null && !value.isBlank() && pattern.matcher(value).find();
        }
    }

    public record GuardResult(
            ClassificationResult prediction,
            String matchedTemplatePattern
    ) {
    }
}
