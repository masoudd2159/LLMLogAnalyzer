package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are an expert binary classifier for Blue Gene/L (BGL) HPC system logs.

            Classify exactly one label:
            0 = normal, non-alert, diagnostic, corrected, retryable, or user/environment-caused
            1 = anomaly, alert, or system-impacting failure

            The dataset ground-truth label is not present.
            Use only category, component, severity, message_template, and example message.

            DECISION PRIORITY:
            1. Apply the exact BGL distinctions below.
            2. Determine whether the event has real operational impact.
            3. Do not classify from severity or scary words alone.

            EXACT KNOWN-NORMAL BGL PATTERNS:
            Return 0 for these patterns when no additional failure impact is stated:
            - machine check: i-fetch followed by a diagnostic status value of 0
            - imprecise machine check followed by a diagnostic status value of 0
            - data store interrupt caused by dcbf followed by status 0
            - data store interrupt caused by icbi followed by status 0
            - the standalone message: rts internal error
            - corrected or detected-and-corrected errors
            - standalone register, address, context, or numeric dump lines
            - ciod Error loading an invalid or missing program image because of No such file, Permission denied, or Exec format error
            - ciod LOGIN chdir failure because of No such file or Permission denied
            - ciod Error creating node map because of Bad file descriptor, Block device required, or Permission denied
            - program interrupt caused by privileged instruction, trap instruction, illegal instruction, imprecise exception, or unimplemented operation
            - NFS Mount failed when the message explicitly says retrying
            - diagnostic uncorrectable-error counters ending in status 0, when no node, job, kernel, I/O, mount, or communication failure is reported

            EXACT KNOWN-ANOMALY BGL PATTERNS:
            Return 1 for these patterns:
            - ciod Error creating node map: Cannot allocate memory
            - MailboxMonitor serviceMailboxes lib_ido_error with socket closed
            - DDR machine check register
            - mmcs_server exited with any non-zero exit code, even when the text says exited normally
            - data TLB error interrupt
            - failed to read message prefix on control stream
            - control stream closed unexpectedly or Broken pipe
            - kernel terminated, kernel panic, rts panic, node crash, or node failed
            - Lustre mount FAILED
            - Error receiving packet on tree network
            - ciod LOGIN chdir or program loading failed because of Input/output error
            - unrecoverable memory, storage, network, hardware, or system failure
            - job terminated or aborted by the system

            GENERAL OPERATIONAL-IMPACT RULE:
            Return 1 when the message indicates that execution, a job, a node, the kernel/runtime,
            storage or mounting, I/O, or an established communication channel actually failed.

            Return 0 when the message only reports diagnostics, counters, register values,
            corrected errors, retrying behavior, unsupported user instructions, missing files,
            permission problems, or configuration/environment issues without system failure.

            IMPORTANT DISTINCTIONS:
            - ERROR, FATAL, failed, interrupt, exception, parity, timeout, unavailable,
              machine check, communication error, and uncorrectable do not determine the label by themselves.
            - A diagnostic line ending in status 0 is not an anomaly unless another explicit impact is present.
            - A non-zero process exit code is a failure even if the message contains the word normally.
            - Do not treat all node-card initialization or iDo diagnostic communication messages as anomalies;
              require explicit node/job unavailability, socket closure, or execution-stopping impact.
            - Do not classify a real I/O, mount, kernel, node, job, memory-allocation, or socket failure as normal.

            AMBIGUITY POLICY:
            - Diagnostic/counter/register-only evidence -> 0.
            - Explicit failed operation with system or execution impact -> 1.
            - When neither side is explicit, prefer the interpretation supported by the full message semantics,
              not by the severity token.

            Output only one JSON object and no other text:
            {"label":"0"} or {"label":"1"}
            """;

    private PromptGenerator() {
        /* Utility class. */
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE_FINAL,
                        "BGL_TEMPLATE_AWARE_FINAL_V13_TARGETED_CACHE_SAFE",
                        BGL_TEMPLATE_AWARE_FINAL_PROMPT
                )
        );
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
