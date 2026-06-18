package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Conservative deterministic BGL template guard.
 * <p>
 * This class is not a replacement for the LLM.
 * It only classifies templates that are stable and repeatedly observed in BGL.
 * Unknown or ambiguous templates must still go to the LLM.
 * <p>
 * Important design rule:
 * Do not add broad keyword rules here.
 * Words such as failed, fatal, interrupt, uncorrectable, unavailable, timeout,
 * or not accessible are not enough by themselves.
 */
public final class BglTemplateGuard {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL;

    /*
     * Known-normal overrides are checked first.
     * These rules exist because some BGL messages contain scary words such as
     * "uncorrectable", "interrupt", "failed", "not accessible", "parity",
     * or "FATAL", but are labeled as NORMAL in the dataset or behave as
     * diagnostic/non-alert records.
     */
    private static final List<TemplateRule> KNOWN_NORMAL_RULES = List.of(
            rule("NORMAL_CIOD_ERROR_OPENING_NODE_MAP_NO_SUCH_FILE",
                    "ciod:\\s+Error\\s+opening\\s+node\\s+map\\s+file\\s+.*No\\s+such\\s+file\\s+or\\s+directory"),

            rule("NORMAL_IDO_PACKET_TIMEOUT",
                    "\\bIdo\\s+packet\\s+timeout\\b"),

            rule("NORMAL_LINK_OR_NODE_CARD_POWER_MODULE_NOT_ACCESSIBLE",
                    "(?:LinkCard|NodeCard).*power\\s+module.*(?:is\\s+)?not\\s+accessible"),

            rule("NORMAL_UNCORRECTABLE_ERROR_ADDRESS_CAPTURE",
                    "capture\\s+first\\s+.*uncorrectable\\s+error\\s+address"),

            rule("NORMAL_UNCORRECTABLE_ERROR_DETECTED_DIAGNOSTIC",
                    "uncorrectable\\s+error\\s+detected\\s+in\\s+(?:directory|EDRAM|external\\s+DDR|DDR)"),

            rule("NORMAL_MEMORY_MANAGER_UNCORRECTABLE_DIAGNOSTIC",
                    "memory\\s+manager\\s+uncorrectable\\s+error"),

            rule("NORMAL_GENERIC_UNCORRECTABLE_ERROR_COUNTER",
                    "^\\s*uncorrectable\\s+error\\b.*(?:<NUM>|\\d+)\\s*$"),

            rule("NORMAL_DATA_STORAGE_INTERRUPT",
                    "^\\s*data\\s+storage\\s+interrupt\\s*$"),

            rule("NORMAL_DDR_EXCESSIVE_SOFT_FAILURES",
                    "\\bddr:\\s+excessive\\s+soft\\s+failures,\\s+consider\\s+replacing\\s+the\\s+card\\b"),

            rule("NORMAL_PLB_TLB_CACHE_COUNTER_DIAGNOSTICS",
                    "(?:^|\\s)(?:instruction\\s+plb\\s+error"
                            + "|data\\s+(?:read|write)\\s+plb\\s+error"
                            + "|tlb\\s+error"
                            + "|i-cache\\s+parity\\s+error"
                            + "|d-cache\\s+(?:search|flush|tag)\\s+parity\\s+error"
                            + "|critical\\s+input\\s+interrupt\\s+enable)\\.*\\s*(?:<NUM>|\\d+)\\b"),

            rule("NORMAL_HEX_REGISTER_DUMP_LINE",
                    "(?:^|\\s)(?:\\d+|<NUM>):(?:[0-9a-f]{8}|<HEX>|<NUM>)"
                            + "(?:\\s+(?:\\d+|<NUM>):(?:[0-9a-f]{8}|<HEX>|<NUM>)){1,}"),

            rule("NORMAL_CIOD_LOGIN_CHDIR_PERMISSION_DENIED",
                    "ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+Permission\\s+denied")
    );

    /*
     * Strong anomaly rules.
     * These should represent clear system-impacting failures, not keyword matches.
     */
    private static final List<TemplateRule> ANOMALY_RULES = List.of(
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
                    "^\\s*machine\\s+check\\s+interrupt\\s*$"),

            rule("ANOMALY_UNRECOVERABLE_SYSTEM_FAILURE",
                    "\\bunrecoverable\\s+(?:system|hardware|memory|storage|network|error|failure)\\b"),

            rule("ANOMALY_FATAL_HARDWARE_FAILURE",
                    "\\bfatal\\s+hardware\\b|\\bhardware\\s+failure\\b|\\bpower\\s+failure\\b|\\bfan\\s+failure\\b|\\bthermal\\s+failure\\b|\\btemperature\\s+critical\\b"),

            rule("ANOMALY_JOB_OR_NODE_TERMINATED",
                    "\\bjob\\s+terminated\\b|\\bnode\\s+crash\\b|\\bnode\\s+failed\\b|\\baborted\\s+by\\s+system\\b")
    );

    /*
     * General known-normal rules.
     * These are checked after strong anomaly rules, except for KNOWN_NORMAL_RULES above.
     */
    private static final List<TemplateRule> NORMAL_RULES = List.of(
            rule("NORMAL_CIOD_ERROR_LOADING_NO_SUCH_FILE",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*No\\s+such\\s+file\\s+or\\s+directory"),

            rule("NORMAL_CIOD_ERROR_LOADING_PERMISSION_DENIED",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*Permission\\s+denied"),

            rule("NORMAL_CIOD_ERROR_LOADING_EXEC_FORMAT",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*Exec\\s+format\\s+error"),

            rule("NORMAL_CIOD_LOGIN_CHDIR_NO_SUCH_FILE",
                    "ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+No\\s+such\\s+file\\s+or\\s+directory"),

            rule("NORMAL_CIOD_NODE_MAP_BAD_FILE_DESCRIPTOR",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Bad\\s+file\\s+descriptor"),

            rule("NORMAL_CIOD_NODE_MAP_BLOCK_DEVICE_REQUIRED",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Block\\s+device\\s+required"),

            rule("NORMAL_CIOD_NODE_MAP_PERMISSION_DENIED",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Permission\\s+denied"),

            rule("NORMAL_EXCEPTION_SYNDROME_REGISTER",
                    "exception\\s+syndrome\\s+register:\\s*(?:<HEX>|0x[0-9a-f]+)"),

            rule("NORMAL_PROGRAM_INTERRUPT",
                    "program\\s+interrupt:\\s+(?:privileged\\s+instruction|trap\\s+instruction|imprecise\\s+exception|illegal\\s+instruction|unimplemented\\s+operation)"),

            rule("NORMAL_MACHINE_CHECK_I_FETCH",
                    "machine\\s+check:\\s+i-fetch"),

            rule("NORMAL_DATA_STORE_INTERRUPT_DCBF_ICBI",
                    "data\\s+store\\s+interrupt\\s+caused\\s+by\\s+(?:dcbf|icbi)"),

            rule("NORMAL_REGISTER_OR_ADDRESS_DIAGNOSTIC",
                    "instruction\\s+address:|data\\s+address:|core\\s+configuration\\s+register:|machine\\s+state\\s+register:|floating\\s+point\\s+status|data\\s+address\\s+space|store\\s+operation|byte\\s+ordering\\s+exception"),

            rule("NORMAL_GENERATING_CORE_OR_RTS_INTERNAL",
                    "generating\\s+core|rts\\s+internal\\s+error"),

            rule("NORMAL_DEBUGGER_DIED",
                    "ciod:\\s+pollControlDescriptors:\\s+Detected\\s+the\\s+debugger\\s+died"),

            rule("NORMAL_NFS_MOUNT_RETRYING",
                    "NFS\\s+Mount\\s+failed\\s+.*retrying"),

            rule("NORMAL_RTS_TREE_TORUS_LINK_TRAINING_FAILED",
                    "rts\\s+tree/torus\\s+link\\s+training\\s+failed"),

            rule("NORMAL_RTS_BAD_MESSAGE_HEADER",
                    "rts:\\s+bad\\s+message\\s+header"),

            rule("NORMAL_ASSERT_CONDITION",
                    "ASSERT\\s+condition"),

            rule("NORMAL_NODE_CARD_NOT_FULLY_FUNCTIONAL",
                    "Node\\s*card\\s+is\\s+not\\s+fully\\s+functional|NodeCard\\s+is\\s+not\\s+fully\\s+functional"),

            rule("NORMAL_CAN_NOT_GET_ASSEMBLY_INFORMATION",
                    "Can\\s+not\\s+get\\s+assembly\\s+information\\s+for\\s+node\\s+card"),

            rule("NORMAL_CORRECTED",
                    "detected\\s+and\\s+corrected|\\bcorrected\\b|\\bCE\\s+sym\\b")
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

        Optional<GuardResult> knownNormal = firstMatch(
                KNOWN_NORMAL_RULES,
                ClassificationResult.NORMAL,
                raw,
                normalized
        );
        if (knownNormal.isPresent()) {
            return knownNormal;
        }

        Optional<GuardResult> anomaly = firstMatch(
                ANOMALY_RULES,
                ClassificationResult.ANOMALY,
                raw,
                normalized
        );
        if (anomaly.isPresent()) {
            return anomaly;
        }

        return firstMatch(
                NORMAL_RULES,
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
