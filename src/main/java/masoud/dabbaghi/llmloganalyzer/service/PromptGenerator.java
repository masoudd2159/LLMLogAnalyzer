package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are a conservative BGL Blue Gene/L log template classifier.
            Classify one label only:
            0 = normal / non-alert
            1 = anomaly / alert
            
            The dataset label is not present. Use only category, component, severity, message_template, and example message.
            Message meaning has higher priority than severity words.
            Words such as FATAL, ERROR, failed, interrupt, exception, ASSERT, parity, timeout, and failure are not enough alone.
            
            Return 0 for known normal/non-alert patterns:
            - corrected or detected and corrected errors
            - diagnostic/register/context messages such as exception syndrome register, machine state register, instruction address, data address, and generating core
            - standalone register dump lines or numeric diagnostic counter lines
            - program interrupt: privileged instruction, trap instruction, illegal instruction, imprecise exception, or unimplemented operation
            - standalone data storage interrupt line without explicit unrecovered impact
            - data store interrupt caused by dcbf or icbi
            - machine check: i-fetch
            - DDR excessive soft failures with recommendation to replace the card
            - PLB/TLB/cache parity diagnostic counters ending in a numeric value
            - critical input interrupt enable diagnostic counter ending in a numeric value
            - ciod Error loading invalid/missing program image due to No such file, Permission denied, or Exec format error
            - ciod LOGIN chdir(...) failed due to No such file or Permission denied
            - ciod Error creating node map due to Bad file descriptor, Block device required, or Permission denied
            - rts bad message header, rts tree/torus link training failed, rts internal error
            - NFS Mount failed ... retrying
            - Node card is not fully functional, Can not get assembly information for node card
            - ASSERT condition without explicit unrecovered system impact
            
            Return 1 for known anomaly/alert patterns:
            - data TLB error interrupt
            - kernel terminated, kernel panic, rts panic, node crash
            - Lustre mount FAILED
            - failed to read message prefix on control stream, control stream closed unexpectedly
            - Error receiving packet on tree network
            - ciod Error creating node map ... No child processes
            - ciod LOGIN chdir(...) failed: Input/output error
            - unrecoverable memory/storage/system failure when it indicates unrecovered system impact
            - power, fan, thermal, temperature, hardware, or link failure causing unrecovered system impact
            
            If ambiguous, return 1 only for explicit unrecovered, persistent, communication-breaking, mount-failing, kernel-terminating, node-crashing, or job-killing failure.
            Otherwise return 0.
            
            Output only one JSON object: {"label":"0"} or {"label":"1"}
            """;

    private PromptGenerator() {
        /* Utility class. */
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE_FINAL,
                        "BGL_TEMPLATE_AWARE_FINAL_V11_FP_REDUCED",
                        BGL_TEMPLATE_AWARE_FINAL_PROMPT
                )
        );
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
