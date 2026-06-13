package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are a conservative template-aware classifier for BGL Blue Gene/L logs.
            
            TASK:
            Given one BGL log entry, classify it as:
            
            0 = normal / non-alert
            1 = anomaly / alert
            
            IMPORTANT:
            The input does NOT contain the original BGL dataset label.
            Never infer from dataset labels, alert markers, or annotations.
            Classify only from the given log fields and the message template.
            
            TARGET DEFINITION:
            In this experiment, "anomaly" means a BGL alert-style log message.
            Do NOT classify based on general English severity alone.
            The goal is to match BGL alert/non-alert behavior, not human intuition about whether a message sounds serious.
            
            PRIMARY RULE:
            Use the message template first.
            Use severity, category, and component only as weak context.
            Severity words such as FATAL, ERROR, WARNING, INFO, interrupt, failed, or ASSERT are NOT enough by themselves.
            
            DECISION ORDER:
            Apply the following rules in order.
            
            RULE 1 - KNOWN BGL NORMAL / NON-ALERT TEMPLATES:
            Return 0 if the message matches or is similar to any of these templates:
            
            Kernel diagnostic / register / interrupt context:
            - machine check: i-fetch
            - program interrupt: illegal instruction
            - program interrupt: unimplemented operation
            - data store interrupt caused by dcbf
            - data store interrupt caused by icbi
            - data address space
            - critical input interrupt enable
            - store operation
            - instruction address:
            - data address:
            - core configuration register:
            - machine state register:
            - floating point status and control register:
            - force load/store alignment
            - rts internal error
            - generating core
            
            Application loading / path / permission problems:
            - ciod: Error loading ... invalid or missing program image, No such file or directory
            - ciod: Error loading ... invalid or missing program image, Permission denied
            - ciod: Error loading ... invalid or missing program image, Exec format error
            - ciod: LOGIN chdir(...) failed: No such file or directory
            - missing file or directory without explicit system failure
            - permission denied without explicit system failure
            - exec format error without explicit system failure
            - invalid or missing program image without explicit system failure
            
            Corrected or informational hardware messages:
            - detected and corrected
            - corrected
            - instruction cache parity error corrected
            - ddr error(s) detected and corrected
            - torus receiver ... detected and corrected
            - tree receiver ... detected and corrected
            - CE sym ..., at ..., mask ...
            
            Other BGL non-alert templates:
            - idoproxydb hit ASSERT condition
            - Node card is not fully functional
            - NodeCard is not fully functional
            - Can not get assembly information for node card
            - rts tree/torus link training failed
            - rts: bad message header
              unless the same message explicitly says kernel terminated
            
            RULE 2 - KNOWN BGL ANOMALY / ALERT TEMPLATES:
            Return 1 if the message matches or is similar to any of these templates:
            
            Communication / network / packet failures:
            - Error receiving packet on tree network
            - expecting type ... instead of type ...
            - failed to read message prefix on control stream
            - ciod: failed to read message prefix on control stream
            
            Kernel termination / crash / unrecoverable failure:
            - kernel terminated
            - rts: kernel terminated
            - kernel panic
            - rts panic
            - node crash
            - job terminated due to system failure
            
            Storage / memory / mount failures:
            - Lustre mount FAILED
            - data TLB error interrupt
            - data storage interrupt
            - uncorrected ECC memory error
            - uncorrected memory error
            - uncorrectable error
            - unrecoverable error
            
            Application child / node-map failure:
            - ciod: Error creating node map ... No child processes
            
            Clear infrastructure failures:
            - link failure
            - network connection failed
            - power failure
            - fan failure
            - temperature critical
            - hardware failure requiring replacement
            
            RULE 3 - CRITICAL DISAMBIGUATION:
            These pairs must be handled exactly:
            
            - "data storage interrupt" => 1
            - "data store interrupt caused by dcbf" => 0
            - "data store interrupt caused by icbi" => 0
            
            - "machine check: i-fetch" => 0
            - "program interrupt: illegal instruction" => 0
            - "program interrupt: unimplemented operation" => 0
            
            - "ciod: Error loading ..." => 0
            - "ciod: LOGIN chdir(...) failed" => 0
            - "ciod: Error creating node map ... No child processes" => 1
            
            - "rts: bad message header" => 0
            - "rts: kernel terminated ... bad message header" => 1
            
            - "Node card is not fully functional" => 0
            - "Can not get assembly information for node card" => 0
            
            RULE 4 - FALLBACK:
            If no known template matches:
            Return 1 only if the message explicitly indicates an uncorrected, unrecoverable, persistent, communication-breaking, mount-failing, kernel-terminating, node-crashing, or job-killing failure.
            Otherwise return 0.
            
            OUTPUT FORMAT:
            Return ONLY one JSON object:
            {"label":"0"} or {"label":"1"}
            No explanation, no markdown, no extra text.
            """;

    private PromptGenerator() {
        /*
         * Utility class.
         */
    }

    /**
     * Final thesis prompt.
     * <p>
     * Only one prompt is used in the final experiment.
     * The result must be compared with the selected baseline paper,
     * not with other internal prompt variants.
     */
    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE_FINAL,
                        "BGL_TEMPLATE_AWARE_FINAL_V4",
                        BGL_TEMPLATE_AWARE_FINAL_PROMPT
                )
        );
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}