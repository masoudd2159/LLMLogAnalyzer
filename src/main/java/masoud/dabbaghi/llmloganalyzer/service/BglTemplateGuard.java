package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Lightweight guard: only high-confidence BGL messages are handled here. */
public final class BglTemplateGuard {

    private static final Set<String> EXACT_NORMAL = Set.of(
            "rts internal error",
            "can not get assembly information for node card",
            "node card is not fully functional",
            "nodecard is not fully functional",
            "ciod: pollcontroldescriptors: detected the debugger died."
    );

    private static final Set<String> EXACT_ANOMALY = Set.of(
            "data storage interrupt"
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
            return anomaly("ANOMALY_EXACT_DATA_STORAGE_INTERRUPT");
        }

        if (message.contains("cannot allocate memory") && message.contains("creating node map")) {
            return anomaly("ANOMALY_NODE_MAP_MEMORY_ALLOCATION");
        }
        if (message.contains("mailboxmonitor::servicemailboxes") && message.contains("socket closed")) {
            return anomaly("ANOMALY_MAILBOX_SOCKET_CLOSED");
        }
        if (message.startsWith("ddr machine check register:")) {
            return anomaly("ANOMALY_DDR_MACHINE_CHECK_REGISTER");
        }
        if (message.contains("exit code <non_zero>")) {
            return anomaly("ANOMALY_NON_ZERO_EXIT_CODE");
        }
        if (message.contains("data tlb error interrupt")) {
            return anomaly("ANOMALY_DATA_TLB_INTERRUPT");
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
