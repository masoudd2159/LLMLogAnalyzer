package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are an expert classifier for Blue Gene/L (BGL) HPC system logs.

            Classify exactly one label:
            0 = normal or non-alert
            1 = anomaly or system-impacting failure

            The dataset label is not present. Use only category, component, severity,
            message_template, and example message.

            MAIN RULE
            Classify from the complete operational meaning of the message.
            Do not default to 0 or 1. Do not decide from severity or one keyword alone.

            EVIDENCE ORDER
            1. Exact BGL message meaning and the distinctions listed below.
            2. Explicit impact on a job, process, node, runtime, storage, I/O, memory,
               or an established communication channel.
            3. Recovery state: corrected, retrying, diagnostic-only, or unrecovered.
            4. Cause: system failure versus user program, file, permission,
               configuration, or debugger/environment issue.
            5. Category and severity are supporting evidence only.

            RETURN 1 FOR CLEAR SYSTEM IMPACT
            - job, node, kernel, or runtime termination
            - unrecovered mount, storage, I/O, memory, network, or hardware failure
            - required system memory allocation failure
            - control/mailbox channel closed or broken
            - non-zero service/process exit code
            - standalone message: data storage interrupt
            - data TLB error interrupt or machine check interrupt
            - kernel panic, kernel terminated, node crash, or node failed

            Known anomaly examples:
            - ciod Error creating node map: Cannot allocate memory
            - MailboxMonitor serviceMailboxes ... socket closed
            - DDR machine check register
            - mmcs_server exited normally with a non-zero exit code
            - failed to read message prefix on control stream
            - control stream closed unexpectedly or Broken pipe
            - Lustre mount FAILED
            - Error receiving packet on tree network
            - ciod operation failed because of Input/output error

            RETURN 0 FOR CLEAR NON-SYSTEM IMPACT
            - detected-and-corrected errors
            - register, address, counter, syndrome, or context dumps
            - retry messages that explicitly say retrying
            - missing file, permission denied, invalid executable, exec format error,
              or program image too big
            - unsupported or invalid user-program instruction
            - debugger termination without node/runtime failure

            Known normal examples:
            - machine check: i-fetch ... 0
            - imprecise machine check ... 0
            - data store interrupt caused by dcbf ... 0
            - data store interrupt caused by icbi ... 0
            - rts internal error
            - Can not get assembly information for node card
            - Node card is not fully functional
            - NodeCard is not fully functional
            - ciod: pollControlDescriptors: Detected the debugger died.
            - program interrupt: fp cr update
            - rts tree/torus link training failed with wanted/got diagnostics
            - rts: bad message header with header/register diagnostics

            CRITICAL DISTINCTIONS
            - data storage interrupt = 1
              data store interrupt caused by dcbf/icbi ... 0 = 0
            - machine check interrupt = 1
              machine check: i-fetch ... 0 = 0
            - non-zero exit code = 1 even when the text says exited normally
            - ERROR, FATAL, failed, interrupt, exception, timeout, unavailable,
              parity, uncorrectable, and machine check are not labels by themselves
            - severity FATAL supports interpretation but never decides the label alone

            BALANCED DECISION PROCESS
            A. Identify the subject and event.
            B. Determine the final operational impact.
            C. Check whether the event was corrected, retried, or only diagnostic.
            D. Apply the exact BGL distinctions before general wording.
            E. Choose the class with stronger evidence; never use class frequency.

            When evidence conflicts, the most specific statement of final operational
            outcome wins. Explicit system failure overrides generic diagnostic wording.
            Explicit corrected/retrying/user-environment evidence overrides scary words
            when no system-impacting consequence is stated.

            Return only one JSON object:
            {"label":"0"} or {"label":"1"}
            """;

    private PromptGenerator() {
        /* Utility class. */
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE_FINAL,
                        "BGL_TEMPLATE_AWARE_FINAL_V14_BALANCED_EVIDENCE",
                        BGL_TEMPLATE_AWARE_FINAL_PROMPT
                )
        );
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
