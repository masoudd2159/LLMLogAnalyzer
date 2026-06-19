package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight deterministic BGL guard.
 * <p>
 * Only narrow, high-confidence BGL messages are classified here.
 * Ambiguous messages are delegated to the LLM.
 */
public final class BglTemplateGuard {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Set<String> EXACT_NORMAL = Set.of(
            "rts internal error",
            "can not get assembly information for node card",
            "node card is not fully functional",
            "nodecard is not fully functional",
            "ciod: pollcontroldescriptors: detected the debugger died."
    );

    private static final Set<String> EXACT_ANOMALY = Set.of(
            "data storage interrupt",
            "machine check interrupt"
    );

    private static final List<String> NORMAL_DIAGNOSTIC_PREFIXES = List.of(
            "critical input interrupt enable",
            "problem state (",
            "store operation",
            "instruction plb error",
            "data write plb error",
            "tlb error",
            "d-cache search parity error",
            "close edram pages as soon as possible",
            "disable all access to cache directory",
            "capture first directory uncorrectable error address",
            "capture first edram uncorrectable error address",
            "capture first ddr uncorrectable error address",
            "uncorrectable error detected in directory",
            "uncorrectable error detected in edram bank",
            "uncorrectable error detected in external ddr",
            "memory manager uncorrectable error",
            "uncorrectable error",
            "parity error in read queue plb",
            "ddr failing address register:",
            "ddr failing data registers:"
    );

    private static final Pattern REGISTER_DUMP = Pattern.compile(
            "^(?:\\d+|<num>):(?:[0-9a-f]{8}|<hex>|<num>)"
                    + "(?:\\s+(?:\\d+|<num>):(?:[0-9a-f]{8}|<hex>|<num>)){1,}$",
            FLAGS
    );

    private BglTemplateGuard() {
    }

    public static Optional<GuardResult> classify(String message) {
        return classify(message, null);
    }

    public static Optional<GuardResult> classify(String rawMessage, String normalizedTemplate) {
        Optional<GuardResult> rawResult = classifyOne(rawMessage);
        return rawResult.isPresent() ? rawResult : classifyOne(normalizedTemplate);
    }

    private static Optional<GuardResult> classifyOne(String value) {
        String message = normalize(value);
        if (message.isBlank()) {
            return Optional.empty();
        }

        if (EXACT_ANOMALY.contains(message)) {
            return anomaly("ANOMALY_EXACT_BGL_EVENT");
        }
        if (containsAll(message, "creating node map", "cannot allocate memory")) {
            return anomaly("ANOMALY_NODE_MAP_MEMORY_ALLOCATION");
        }
        if (containsAll(message, "creating node map", "no child processes")) {
            return anomaly("ANOMALY_NODE_MAP_NO_CHILD_PROCESSES");
        }
        if (containsAll(message, "mailboxmonitor::servicemailboxes", "socket closed")) {
            return anomaly("ANOMALY_MAILBOX_SOCKET_CLOSED");
        }
        if (message.startsWith("ddr machine check register:")) {
            return anomaly("ANOMALY_DDR_MACHINE_CHECK_REGISTER");
        }
        if (message.contains("exit code <non_zero>") || hasRawNonZeroExitCode(message)) {
            return anomaly("ANOMALY_NON_ZERO_EXIT_CODE");
        }
        if (message.contains("data tlb error interrupt")) {
            return anomaly("ANOMALY_DATA_TLB_INTERRUPT");
        }
        if (message.startsWith("machine check interrupt")) {
            return anomaly("ANOMALY_MACHINE_CHECK_INTERRUPT");
        }
        if (message.contains("failed to read message prefix on control stream")
                || message.contains("control stream closed unexpectedly")
                || message.contains("broken pipe")) {
            return anomaly("ANOMALY_CONTROL_STREAM_FAILURE");
        }
        if (message.contains("kernel terminated")
                || message.contains("kernel panic")
                || message.contains("rts panic")) {
            return anomaly("ANOMALY_KERNEL_FAILURE");
        }
        if (message.contains("lustre mount failed")
                || message.contains("error receiving packet on tree network")
                || message.contains("input/output error")) {
            return anomaly("ANOMALY_OPERATIONAL_FAILURE");
        }

        if (EXACT_NORMAL.contains(message)) {
            return normal("NORMAL_EXACT_BGL_STATUS");
        }
        if (containsAll(message, "creating node map", "bad file descriptor")) {
            return normal("NORMAL_NODE_MAP_BAD_FILE_DESCRIPTOR");
        }
        if (message.startsWith("rts tree/torus link training failed:")) {
            return normal("NORMAL_RTS_LINK_TRAINING_DIAGNOSTIC");
        }
        if (message.startsWith("rts: bad message header:")) {
            return normal("NORMAL_RTS_BAD_HEADER_DIAGNOSTIC");
        }
        if (message.startsWith("machine check: i-fetch") && endsWithZero(message)) {
            return normal("NORMAL_MACHINE_CHECK_I_FETCH_ZERO");
        }
        if (message.startsWith("imprecise machine check") && endsWithZero(message)) {
            return normal("NORMAL_IMPRECISE_MACHINE_CHECK_ZERO");
        }
        if (message.startsWith("data store interrupt caused by")
                && (message.contains("dcbf") || message.contains("icbi"))
                && endsWithZero(message)) {
            return normal("NORMAL_DATA_STORE_DIAGNOSTIC_ZERO");
        }
        if (isDiagnosticContinuationLine(message)) {
            return normal("NORMAL_MACHINE_CHECK_DIAGNOSTIC_FIELD");
        }
        if (REGISTER_DUMP.matcher(message).matches()) {
            return normal("NORMAL_REGISTER_DUMP");
        }
        if (message.contains("detected and corrected") || message.contains(" corrected")) {
            return normal("NORMAL_CORRECTED_ERROR");
        }
        if (message.contains("program image too big")
                || message.startsWith("program interrupt: fp cr update")) {
            return normal("NORMAL_USER_PROGRAM_FAILURE");
        }
        if (message.contains("no such file or directory")
                || message.contains("permission denied")
                || message.contains("exec format error")) {
            return normal("NORMAL_USER_ENVIRONMENT_FAILURE");
        }
        if (message.contains("nfs mount failed") && message.contains("retrying")) {
            return normal("NORMAL_RETRYING_MOUNT");
        }

        return Optional.empty();
    }

    private static boolean isDiagnosticContinuationLine(String message) {
        for (String prefix : NORMAL_DIAGNOSTIC_PREFIXES) {
            if (message.startsWith(prefix) && hasTrailingStatusOrRegisterValue(message)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTrailingStatusOrRegisterValue(String value) {
        return value.matches(".*(?:-?\\d+|<num>|<zero>|<non_zero>|<hex>)\\s*$");
    }

    private static boolean hasRawNonZeroExitCode(String value) {
        return value.matches(".*\\bexit\\s+code\\s+(?:-[1-9]\\d*|[1-9]\\d*)\\b.*");
    }

    private static boolean containsAll(String value, String first, String second) {
        return value.contains(first) && value.contains(second);
    }

    private static boolean endsWithZero(String value) {
        return value.endsWith(" 0") || value.endsWith(".0") || value.endsWith("<zero>");
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Optional<GuardResult> normal(String rule) {
        return Optional.of(new GuardResult(ClassificationResult.NORMAL, rule));
    }

    private static Optional<GuardResult> anomaly(String rule) {
        return Optional.of(new GuardResult(ClassificationResult.ANOMALY, rule));
    }

    public record GuardResult(ClassificationResult prediction, String matchedTemplatePattern) {
    }
}
