package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic BGL template guard.
 * <p>
 * This is not a second prompt.
 * It is a template-aware preprocessing step used before calling the LLM.
 * <p>
 * Purpose:
 * - reduce false positives caused by known-normal BGL templates;
 * - preserve high recall by checking reliable known-anomaly templates first;
 * - make frequent BGL templates deterministic and explainable.
 */
public final class BglTemplateGuard {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final List<TemplateRule> ANOMALY_RULES = List.of(
            rule("ANOMALY_DATA_TLB_ERROR_INTERRUPT",
                    "data\\s+TLB\\s+error\\s+interrupt"),

            rule("ANOMALY_DATA_STORAGE_INTERRUPT",
                    "data\\s+storage\\s+interrupt"),

            rule("ANOMALY_CONTROL_STREAM_PREFIX_READ_FAILED",
                    "failed\\s+to\\s+read\\s+message\\s+prefix\\s+on\\s+control\\s+stream"),

            rule("ANOMALY_KERNEL_TERMINATED",
                    "kernel\\s+terminated"),

            rule("ANOMALY_LUSTRE_MOUNT_FAILED",
                    "Lustre\\s+mount\\s+FAILED"),

            rule("ANOMALY_TREE_NETWORK_PACKET_RECEIVE_ERROR",
                    "Error\\s+receiving\\s+packet\\s+on\\s+tree\\s+network"),

            rule("ANOMALY_NODE_MAP_NO_CHILD_PROCESSES",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*No\\s+child\\s+processes"),

            /*
             * Important BGL edge case:
             * "ciod: LOGIN chdir(...) failed: No such file or directory" is treated as known-normal,
             * but "Input/output error" is a stronger I/O/storage-style failure and must not be cached
             * as normal just because it is also a chdir failure.
             */
            rule("ANOMALY_CIOD_LOGIN_CHDIR_IO_ERROR",
                    "ciod:\\s+LOGIN\\s+chdir\\(.*\\)\\s+failed:\\s+Input/output\\s+error"),

            rule("ANOMALY_UNCORRECTED_MEMORY",
                    "uncorrected|uncorrectable|unrecoverable")
    );

    /*
     * These rules target frequent false positives.
     * They are normal / non-alert in the BGL label definition.
     */
    private static final List<TemplateRule> NORMAL_RULES = List.of(
            /*
             * Application loading / path / executable problems.
             */
            rule("NORMAL_CIOD_ERROR_LOADING_NO_SUCH_FILE",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*No\\s+such\\s+file\\s+or\\s+directory"),

            rule("NORMAL_CIOD_ERROR_LOADING_PERMISSION_DENIED",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*Permission\\s+denied"),

            rule("NORMAL_CIOD_ERROR_LOADING_EXEC_FORMAT",
                    "ciod:\\s+Error\\s+loading\\s+.*invalid\\s+or\\s+missing\\s+program\\s+image.*Exec\\s+format\\s+error"),

            rule("NORMAL_CIOD_LOGIN_CHDIR_NO_SUCH_FILE",
                    "ciod:\\s+LOGIN\\s+chdir\\(.*\\)\\s+failed:\\s+No\\s+such\\s+file\\s+or\\s+directory"),

            /*
             * Node-map file problems.
             * No child processes is anomaly and is handled in ANOMALY_RULES first.
             */
            rule("NORMAL_CIOD_NODE_MAP_BAD_FILE_DESCRIPTOR",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Bad\\s+file\\s+descriptor"),

            rule("NORMAL_CIOD_NODE_MAP_BLOCK_DEVICE_REQUIRED",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Block\\s+device\\s+required"),

            rule("NORMAL_CIOD_NODE_MAP_PERMISSION_DENIED",
                    "ciod:\\s+Error\\s+creating\\s+node\\s+map\\s+.*Permission\\s+denied"),

            /*
             * Kernel diagnostic / register / interrupt context.
             */
            rule("NORMAL_EXCEPTION_SYNDROME_REGISTER",
                    "exception\\s+syndrome\\s+register:\\s*0x[0-9a-f]+"),

            rule("NORMAL_PROGRAM_INTERRUPT_PRIVILEGED_INSTRUCTION",
                    "program\\s+interrupt:\\s+privileged\\s+instruction"),

            rule("NORMAL_PROGRAM_INTERRUPT_TRAP_INSTRUCTION",
                    "program\\s+interrupt:\\s+trap\\s+instruction"),

            rule("NORMAL_PROGRAM_INTERRUPT_IMPRECISE_EXCEPTION",
                    "program\\s+interrupt:\\s+imprecise\\s+exception"),

            rule("NORMAL_PROGRAM_INTERRUPT_ILLEGAL_INSTRUCTION",
                    "program\\s+interrupt:\\s+illegal\\s+instruction"),

            rule("NORMAL_PROGRAM_INTERRUPT_UNIMPLEMENTED_OPERATION",
                    "program\\s+interrupt:\\s+unimplemented\\s+operation"),

            rule("NORMAL_MACHINE_CHECK_I_FETCH",
                    "machine\\s+check:\\s+i-fetch"),

            rule("NORMAL_DATA_STORE_INTERRUPT_DCBF",
                    "data\\s+store\\s+interrupt\\s+caused\\s+by\\s+dcbf"),

            rule("NORMAL_DATA_STORE_INTERRUPT_ICBI",
                    "data\\s+store\\s+interrupt\\s+caused\\s+by\\s+icbi"),

            rule("NORMAL_DATA_ADDRESS_SPACE",
                    "data\\s+address\\s+space"),

            rule("NORMAL_STORE_OPERATION",
                    "store\\s+operation"),

            rule("NORMAL_BYTE_ORDERING_EXCEPTION",
                    "byte\\s+ordering\\s+exception"),

            rule("NORMAL_RTS_INTERNAL_ERROR",
                    "rts\\s+internal\\s+error"),

            /*
             * Other known-normal BGL-like templates.
             */
            rule("NORMAL_DEBUGGER_DIED",
                    "ciod:\\s+pollControlDescriptors:\\s+Detected\\s+the\\s+debugger\\s+died"),

            rule("NORMAL_NFS_MOUNT_RETRYING",
                    "NFS\\s+Mount\\s+failed\\s+.*slept\\s+.*retrying"),

            rule("NORMAL_RTS_TREE_TORUS_LINK_TRAINING_FAILED",
                    "rts\\s+tree/torus\\s+link\\s+training\\s+failed"),

            /*
             * Important:
             * This must be NORMAL, even if the message contains:
             * "expecting type ... instead of type ..."
             */
            rule("NORMAL_RTS_BAD_MESSAGE_HEADER",
                    "rts:\\s+bad\\s+message\\s+header"),

            rule("NORMAL_ASSERT_CONDITION",
                    "ASSERT\\s+condition"),

            rule("NORMAL_NODE_CARD_NOT_FULLY_FUNCTIONAL",
                    "Node\\s*card\\s+is\\s+not\\s+fully\\s+functional|NodeCard\\s+is\\s+not\\s+fully\\s+functional"),

            rule("NORMAL_CAN_NOT_GET_ASSEMBLY_INFORMATION",
                    "Can\\s+not\\s+get\\s+assembly\\s+information\\s+for\\s+node\\s+card"),

            rule("NORMAL_CORRECTED",
                    "detected\\s+and\\s+corrected|\\bcorrected\\b")
    );

    private BglTemplateGuard() {
        /* Utility class. */
    }

    public static Optional<GuardResult> classify(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        /*
         * Step 1:
         * Check reliable anomaly templates first.
         */
        for (TemplateRule rule : ANOMALY_RULES) {
            if (rule.pattern().matcher(message).find()) {
                return Optional.of(new GuardResult(
                        ClassificationResult.ANOMALY,
                        rule.name()
                ));
            }
        }

        /*
         * Step 2:
         * Check known normal templates.
         */
        for (TemplateRule rule : NORMAL_RULES) {
            if (rule.pattern().matcher(message).find()) {
                return Optional.of(new GuardResult(
                        ClassificationResult.NORMAL,
                        rule.name()
                ));
            }
        }

        /*
         * Step 3:
         * Unknown or ambiguous templates must be sent to the LLM.
         */
        return Optional.empty();
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
