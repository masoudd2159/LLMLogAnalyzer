package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You classify one Blue Gene/L (BGL) log template.

            Output exactly:
            {"label":"0"} for normal/non-alert
            {"label":"1"} for anomaly/system-impacting failure

            Use category, component, severity, message_template, and example message.
            The dataset label is not present.

            DECISION RULE
            Use the complete operational meaning. Do not default to either class.
            Severity and words such as FATAL, ERROR, failed, interrupt, parity,
            timeout, unavailable, uncorrectable, and machine check are not labels alone.

            Return 1 when the line states real impact on a job, process, node,
            kernel/runtime, storage, mount, I/O path, memory allocation, or an
            established communication channel.

            Clear anomaly examples:
            - data storage interrupt
            - machine check interrupt, including a following bit/detail description
            - data TLB error interrupt
            - ciod node-map creation: Cannot allocate memory
            - ciod node-map creation: No child processes
            - mailbox/control stream closed, broken, or unreadable
            - kernel panic or termination, node crash, Lustre mount FAILED
            - Input/output error
            - non-zero service/process exit code, even if text says exited normally

            Return 0 when the line is corrected, retrying, diagnostic-only,
            user-program-caused, or environment-caused without system failure.

            Clear normal examples:
            - ciod node-map creation: Bad file descriptor
            - missing file, Permission denied, Exec format error, program image too big
            - debugger died without node/runtime termination
            - rts internal error
            - machine check: i-fetch ... 0
            - imprecise machine check ... 0
            - data store interrupt caused by dcbf/icbi ... 0
            - rts tree/torus link training failed with wanted/got diagnostics
            - rts: bad message header with header/register diagnostics

            IMPORTANT BGL CONTINUATION-LINE RULE
            A machine-check event can be followed by separate lines that only report
            a status bit, counter, register, address, or context field. Such a line is
            label 0 by itself, even with severity FATAL or words like interrupt/error.

            Diagnostic continuation examples include:
            - critical input interrupt enable ... value
            - problem state (0=sup,1=usr) ... value
            - store operation ... value
            - instruction/data-write PLB error ... value
            - TLB error or d-cache parity error ... value
            - EDRAM/cache control fields ... value
            - uncorrectable-error counters/addresses ... value
            - DDR failing address/data registers
            - numeric register dumps such as index:value index:value

            Critical distinctions:
            - data storage interrupt = 1
              data store interrupt caused by dcbf/icbi ... 0 = 0
            - machine check interrupt = 1
              machine check: i-fetch ... 0 = 0
            - No child processes during node-map creation = 1
              Bad file descriptor during node-map creation = 0

            When evidence conflicts, the most specific final operational outcome wins.
            Choose from message semantics, not class frequency.

            Return only one JSON object and no explanation.
            """;

    private PromptGenerator() {
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(new PromptSpec(
                PromptExperiment.TEMPLATE_AWARE_FINAL,
                "BGL_TEMPLATE_AWARE_FINAL_V15_DIAGNOSTIC_CONTEXT_SAFE",
                BGL_TEMPLATE_AWARE_FINAL_PROMPT
        ));
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
