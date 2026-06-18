package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are a BGL Blue Gene/L HPC log template classifier.
            
            Classify exactly one label:
            0 = normal / non-alert / diagnostic / user-caused / recoverable
            1 = anomaly / alert / system-impacting failure
            
            The dataset label is not present.
            Use only category, component, severity, message_template, and example message.
            
            Core rule:
            Classify by operational impact, not by scary keywords alone.
            
            Return 1 when the message indicates a system-impacting failure, including:
            - kernel terminated, kernel panic, rts panic, node crash, node failed
            - failed to read message prefix on control stream
            - control stream closed unexpectedly, broken pipe, communication stream failure
            - Lustre mount FAILED or storage/mount failure
            - Input/output error during ciod LOGIN chdir or program loading
            - Error receiving packet on tree network
            - data TLB error interrupt
            - machine check interrupt
            - unrecoverable memory, storage, network, hardware, or system failure
            - job termination, aborted by system, unavailable node, or runtime failure that can stop execution
            - repeated runtime/hardware failure that makes a node/card/link unavailable
            
            Return 0 when the message is clearly diagnostic, corrected, user-caused, or non-alert, including:
            - corrected or detected-and-corrected errors
            - register/context dump lines: exception syndrome register, machine state register, instruction address, data address, core configuration register
            - standalone numeric counter/register dump lines
            - ciod Error loading invalid/missing program image caused by No such file, Permission denied, or Exec format error
            - ciod LOGIN chdir failed caused by No such file or Permission denied
            - program interrupt caused by privileged instruction, trap instruction, illegal instruction, imprecise exception, or unimplemented operation
            - NFS Mount failed ... retrying, when it explicitly says retrying
            - ASSERT condition only when there is no runtime, kernel, node, job, I/O, mount, or communication impact
            - diagnostic messages that only report counters, addresses, or internal state
            
            Important distinction:
            Words like ERROR, FATAL, failed, interrupt, exception, parity, timeout, unavailable, and uncorrectable do not decide the label by themselves.
            However, do not hide real failures as normal only because they look common.
            If the message shows execution failure, communication break, I/O failure, mount failure, kernel/runtime termination, node failure, or job-impacting behavior, return 1.
            
            Ambiguity policy:
            If the message has only diagnostic/counter/register information, return 0.
            If the message suggests a real system operation failed or a node/job/runtime could be affected, return 1.
            
            Output only one JSON object:
            {"label":"0"} or {"label":"1"}
            """;

    private PromptGenerator() {
        /* Utility class. */
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE_FINAL,
                        "BGL_TEMPLATE_AWARE_FINAL_V12_BALANCED_FN_AWARE",
                        BGL_TEMPLATE_AWARE_FINAL_PROMPT
                )
        );
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
