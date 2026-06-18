package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic BGL template guard.
 *
 * The guard is intentionally conservative: it handles only templates whose BGL
 * behavior is stable enough to reuse without an LLM call. Unknown templates still
 * go to the LLM, then validation decides whether the LLM result may be cached.
 */
public final class BglTemplateGuard {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final List<TemplateRule> ANOMALY_RULES = List.of(
            rule("ANOMALY_DATA_TLB_ERROR_INTERRUPT",
                    "data\\s+TLB\\s+error\\s+interrupt"),

            rule("ANOMALY_DATA_STORAGE_INTERRUPT",
                    "data\\s+storage\\s+interrupt"),

            rule("ANOMALY_CONTROL_STREAM_PREFIX_READ_FAILED",
                    "(?:ciod:\\s*)?failed\\s+to\\s+read\\s+message\\s+prefix\\s+on\\s+control\\s+stream"),

            rule("ANOMALY_CONTROL_STREAM_CLOSED",
                    "control\\s+stream\\s+closed\\s+unexpectedly|Broken\\s+pipe"),

            rule("ANOMALY_KERNEL_TERMINATED",
                    "(?:rts:\\s*)?kernel\\s+terminated"),

            rule("ANOMALY_KERNEL_OR_RTS_PANIC",
                    "kernel\\s+panic|rts\\s+panic|panic:"),

            rule("ANOMALY_LUSTRE_MOUNT_FAILED",
                    "Lustre\\s+mount\\s+FAILED|mount\\s+FAILED"),

            rule("ANOMALY_TREE_NETWORK_PACKET_RECEIVE_ERROR",
                    "Error\\s+receiving\\s+packet\\s+on\\s+tree\\s+network"),

            rule("ANOMALY_NODE_MAP_NO_CHILD_PROCESSES",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*No\\s+child\\s+processes"),

            rule("ANOMALY_CIOD_LOGIN_CHDIR_IO_ERROR",
                    "ciod:\\s+LOGIN\\s+chdir\\([^)]*\\)\\s+failed:\\s+Input/output\\s+error"),

            rule("ANOMALY_CIOD_LOADING_IO_ERROR",
                    "ciod:\\s+Error\\s+loading\\s+.*Input/output\\s+error"),

            rule("ANOMALY_UNCORRECTED_MEMORY_OR_UNRECOVERABLE",
                    "uncorrected|uncorrectable|unrecoverable|unrecovered"),

            rule("ANOMALY_FATAL_HARDWARE_FAILURE",
                    "fatal\\s+hardware|hardware\\s+failure|power\\s+failure|fan\\s+failure|thermal\\s+failure|temperature\\s+critical"),

            rule("ANOMALY_JOB_OR_NODE_TERMINATED",
                    "job\\s+terminated|node\\s+crash|node\\s+failed|aborted\\s+by\\s+system")
    );

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
                    "exception\\s+syndrome\\s+register:\\s*<HEX>|exception\\s+syndrome\\s+register:\\s*0x[0-9a-f]+"),

            rule("NORMAL_PROGRAM_INTERRUPT",
                    "program\\s+interrupt:\\s+(privileged\\s+instruction|trap\\s+instruction|imprecise\\s+exception|illegal\\s+instruction|unimplemented\\s+operation)"),

            rule("NORMAL_MACHINE_CHECK_I_FETCH",
                    "machine\\s+check:\\s+i-fetch"),

            rule("NORMAL_DATA_STORE_INTERRUPT_DCBF_ICBI",
                    "data\\s+store\\s+interrupt\\s+caused\\s+by\\s+(dcbf|icbi)"),

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
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        for (TemplateRule rule : ANOMALY_RULES) {
            if (rule.pattern().matcher(message).find()) {
                return Optional.of(new GuardResult(ClassificationResult.ANOMALY, rule.name()));
            }
        }

        for (TemplateRule rule : NORMAL_RULES) {
            if (rule.pattern().matcher(message).find()) {
                return Optional.of(new GuardResult(ClassificationResult.NORMAL, rule.name()));
            }
        }

        return Optional.empty();
    }

    public static Optional<GuardResult> classify(String rawMessage, String normalizedTemplate) {
        Optional<GuardResult> normalizedResult = classify(normalizedTemplate);
        if (normalizedResult.isPresent()) {
            return normalizedResult;
        }
        return classify(rawMessage);
    }

    private static TemplateRule rule(String name, String regex) {
        return new TemplateRule(name, Pattern.compile(regex, FLAGS));
    }

    private record TemplateRule(String name, Pattern pattern) {
    }

    public record GuardResult(
            ClassificationResult prediction,
            String matchedTemplatePattern
    ) {
    }
}
