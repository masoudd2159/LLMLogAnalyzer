package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are a binary classifier for Blue Gene/L (BGL) log lines.

            OUTPUT
            Return only {"label":"0"} or {"label":"1"}.
            0 = normal, diagnostic, corrected, retryable, user-program, or environment issue
            1 = explicit system-level anomaly

            CLASSIFICATION THRESHOLD
            Label 1 requires explicit evidence that the system, node, kernel/runtime,
            storage, network, mount, control channel, or job failed or terminated.
            A line is not anomalous merely because category=RAS, severity=FATAL,
            or it contains error, failed, interrupt, parity, illegal, trap,
            privileged, uncorrectable, timeout, unavailable, or machine check.

            VERY IMPORTANT BGL RULE
            Many BGL records are standalone continuation fields from a larger diagnostic
            report. A line that only reports an address, state bit, counter, register,
            syndrome, configuration value, or user-program exception is label 0.
            Do not invent impact that is not written in the current line.

            ALWAYS LABEL 0 FOR THESE BGL LINE TYPES
            - instruction address: <address>
            - instruction address space ... value
            - critical input interrupt enable ... value
            - problem state (0=sup,1=usr) ... value
            - store operation ... value
            - instruction, data-read, or data-write PLB error ... 0
            - TLB/parity/memory-manager status field ... 0
            - numeric register or address dump
            - program interrupt: illegal instruction ... 0
            - program interrupt: privileged instruction ... 0
            - program interrupt: trap instruction ... 0
            - program interrupt: fp cr update
            - corrected error or an explicitly retrying operation
            - missing file, bad file descriptor, permission denied, exec format error,
              invalid executable, program image too big, or debugger died

            The three program-interrupt lines above are user-code exceptions in this
            BGL classification task. They are normal/non-alert unless the same line
            explicitly states system-managed job, node, kernel, or runtime termination.

            LABEL 1 FOR EXPLICIT SYSTEM FAILURE
            - data storage interrupt
            - machine check interrupt as the primary event line
            - data TLB error interrupt
            - kernel panic/termination or node crash/failure
            - ciod node-map creation: Cannot allocate memory
            - ciod node-map creation: No child processes
            - mailbox/control stream closed, broken, or unreadable
            - Lustre mount FAILED or Input/output error
            - non-zero service/process exit code

            CRITICAL DISTINCTIONS
            data storage interrupt = 1
            data store interrupt caused by dcbf/icbi ... 0 = 0

            machine check interrupt = 1
            machine check: i-fetch ... 0 = 0
            diagnostic fields following a machine-check event = 0

            No child processes during node-map creation = 1
            Bad file descriptor during node-map creation = 0

            DECISION PROCEDURE
            1. Is this an event line or only a diagnostic/context field?
            2. Does the current line explicitly state system-level impact?
            3. Was it corrected, retried, user-caused, or environment-caused?
            4. Apply the exact distinctions above.

            When system-level impact is not explicit and the line is a diagnostic field
            or user-program exception, return 0. Never use class frequency.
            """;

    private PromptGenerator() {
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(new PromptSpec(
                PromptExperiment.TEMPLATE_AWARE_FINAL,
                "BGL_TEMPLATE_AWARE_FINAL_V16_EXPLICIT_SYSTEM_IMPACT",
                BGL_TEMPLATE_AWARE_FINAL_PROMPT
        ));
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
