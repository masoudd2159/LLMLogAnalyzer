package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    /**
     * Shorter V9 prompt for ambiguous templates only.
     * Most frequent deterministic templates should be handled by BglTemplateGuard before this prompt is used.
     */
    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are a conservative classifier for BGL Blue Gene/L log templates.

            TASK:
            Classify the given BGL log/template as:
            0 = normal / non-alert
            1 = anomaly / alert

            IMPORTANT:
            The original BGL dataset label is not included.
            Use only category, component, severity, message_template, and example message.
            Message meaning has higher priority than severity words.
            Words like FATAL, ERROR, failed, interrupt, exception, ASSERT, and bad are weak signals only.

            HIGH-CONFIDENCE NORMAL / NON-ALERT:
            Return 0 for templates equivalent to:
            - ciod Error loading invalid/missing program image with No such file, Permission denied, or Exec format error
            - ciod LOGIN chdir(...) failed: No such file or directory
            - ciod Error creating node map with Bad file descriptor, Block device required, or Permission denied
            - program interrupt: privileged/trap/imprecise/illegal/unimplemented operation
            - exception syndrome register, instruction/data address, machine state register, diagnostic register context
            - machine check: i-fetch
            - data store interrupt caused by dcbf or icbi
            - rts bad message header, rts tree/torus link training failed, rts internal error
            - NFS mount failed but retrying
            - detected and corrected, corrected, CE sym
            - Node card not fully functional, cannot get assembly information
            - ASSERT condition without explicit unrecovered system impact

            HIGH-CONFIDENCE ANOMALY / ALERT:
            Return 1 for templates equivalent to:
            - data TLB error interrupt or data storage interrupt
            - failed to read message prefix on control stream or control stream closed unexpectedly
            - Error receiving packet on tree network
            - kernel terminated, kernel panic, rts panic, node crash, job terminated by system failure
            - Lustre mount FAILED or unrecovered mount failure
            - uncorrected, uncorrectable, unrecoverable, unrecovered memory/storage/hardware failure
            - ciod Error creating node map with No child processes
            - ciod LOGIN chdir(...) failed: Input/output error
            - ciod Error loading ... Input/output error
            - power/fan/thermal/hardware failure requiring action

            CRITICAL DISAMBIGUATION:
            - data storage interrupt => 1
            - data store interrupt caused by dcbf/icbi => 0
            - ciod LOGIN chdir(...) failed: No such file or directory => 0
            - ciod LOGIN chdir(...) failed: Input/output error => 1
            - ciod Error creating node map ... No child processes => 1
            - ciod Error creating node map ... Bad file descriptor / Block device required / Permission denied => 0
            - rts bad message header => 0
            - rts kernel terminated ... bad message header => 1
            - corrected or detected and corrected => 0
            - uncorrected or unrecoverable => 1

            FALLBACK:
            Return 1 only for explicit unrecovered, uncorrected, kernel-terminating, node-crashing, communication-breaking, mount-failing, or job-killing failure.
            Otherwise return 0.

            OUTPUT:
            Return only one JSON object: {"label":"0"} or {"label":"1"}.
            """;

    private PromptGenerator() {
        /* Utility class. */
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE_FINAL,
                        "BGL_TEMPLATE_AWARE_FINAL_V9_FAST_CACHE",
                        BGL_TEMPLATE_AWARE_FINAL_PROMPT
                )
        );
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
