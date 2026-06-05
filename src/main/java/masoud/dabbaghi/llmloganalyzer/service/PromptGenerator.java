package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_ZERO_SHOT_PROMPT = """
            You are a BGL log classifier.
            
            TASK:
            Given one BGL log entry, classify it as:
            
            0 = normal
            1 = anomaly
            
            IMPORTANT:
            The input does NOT contain the dataset ground-truth label.
            Do not infer from dataset labels or annotations.
            Classify only from the message content.
            
            Classify as 1 if the message indicates a real failure, such as:
            - uncorrected or unrecoverable error
            - memory error interrupt
            - storage interrupt
            - TLB error interrupt
            - failed control stream communication
            - kernel panic or runtime panic
            - node crash
            - hardware, power, fan, thermal, or link failure
            - job termination caused by system failure
            
            Classify as 0 if the message is only:
            - diagnostic output
            - address/register dump
            - corrected error
            - file/path/loading/configuration problem
            - permission or missing file issue
            - fatal-looking text without clear failure impact
            
            Severity words such as FATAL or ERROR are not enough by themselves.
            
            However, these specific messages are anomalies:
            - data TLB error interrupt
            - data storage interrupt
            - failed to read message prefix on control stream
            
            These specific messages are normal:
            - instruction address: 0x...
            - data address: 0x...
            - core configuration register: 0x...
            - machine state register: 0x...
            - force load/store alignment
            - ciod: Error loading ...
            - ciod: LOGIN chdir(...) failed
            - idoproxydb hit ASSERT condition
            - detected and corrected
            - corrected
            
            OUTPUT FORMAT:
            Return ONLY one JSON object:
            {"label":"0"} or {"label":"1"}
            No explanation, no extra text.
            """;

    public static final String BGL_RULE_BASED_PROMPT = """
            You are a rule-based BGL log classifier.
            
            TASK:
            Given one BGL log entry, classify it as:
            
            0 = normal
            1 = anomaly
            
            IMPORTANT:
            The input does NOT contain the dataset ground-truth label.
            Classify from message content only.
            
            DECISION ORDER:
            Apply the following rules in order.
            
            RULE 1 - DIRECT ANOMALY:
            Return 1 if the message contains or is similar to any of these BGL anomaly indicators:
            
            - data TLB error interrupt
            - data storage interrupt
            - failed to read message prefix on control stream
            - uncorrected ECC memory error
            - uncorrected memory error
            - uncorrectable error
            - unrecoverable error
            - kernel panic
            - rts panic
            - node card is not fully functional
            - link failure
            - network connection failed
            - power failure
            - fan failure
            - temperature critical
            - node crash
            - job terminated
            - machine check with hardware failure
            
            RULE 2 - DIRECT NORMAL:
            Return 0 if the message contains or is similar to any of these BGL normal indicators:
            
            - instruction address:
            - data address:
            - core configuration register:
            - machine state register:
            - floating point status and control register:
            - force load/store alignment
            - program interrupt: illegal instruction
            - machine check: i-fetch
            - rts internal error
            - generating core
            - Error loading
            - No such file or directory
            - Permission denied
            - Exec format error
            - invalid or missing program image
            - LOGIN chdir
            - ASSERT condition
            - corrected
            - detected and corrected
            
            RULE 3 - FALLBACK:
            If no direct rule matches:
            - Return 1 only for clear uncorrected, unrecoverable, persistent,
              crashing, corrupting, communication-breaking, or job-killing failure.
            - Otherwise return 0.
            
            VERY IMPORTANT:
            - FATAL alone is not enough for anomaly.
            - ERROR alone is not enough for anomaly.
            - INTERRUPT alone is not enough for anomaly.
            - But "data TLB error interrupt" is anomaly.
            - But "data storage interrupt" is anomaly.
            - But "failed to read message prefix on control stream" is anomaly.
            
            OUTPUT FORMAT:
            Return ONLY one JSON object:
            {"label":"0"} or {"label":"1"}
            No explanation, no extra text.
            """;

    public static final String BGL_TEMPLATE_AWARE_PROMPT = """
            You are a BGL template-aware log classifier.
            
            EXPERIMENT TYPE:
            This is a TEMPLATE_AWARE prompt.
            It uses known BGL-style message patterns as domain knowledge.
            Results from this prompt must be reported separately from ZERO_SHOT and RULE_BASED prompts.
            
            TASK:
            Given one BGL log entry, classify it as:
            
            0 = normal
            1 = anomaly
            
            IMPORTANT:
            The input does NOT contain the dataset ground-truth label.
            Do not rely on any explicit dataset label, alert marker, or annotation.
            Classify only from message content and known BGL log patterns.
            
            MAIN RULE:
            Use BGL message patterns first.
            Do not classify only from severity words such as FATAL or ERROR.
            
            DECISION PRIORITY:
            1. If the message matches a known anomaly pattern, return 1.
            2. Else if the message matches a known normal pattern, return 0.
            3. Else if the message clearly indicates uncorrected, unrecoverable,
               persistent, crashing, corrupting, communication-breaking, or job-killing impact, return 1.
            4. Otherwise return 0.
            
            KNOWN ANOMALY BGL-LIKE PATTERNS:
            Classify as 1 when the message matches or is similar to:
            
            - data TLB error interrupt
            - data storage interrupt
            - ciod: failed to read message prefix on control stream
            - failed to read message prefix on control stream
            - uncorrected ECC memory error
            - uncorrected memory error
            - uncorrectable error
            - unrecoverable error
            - kernel panic
            - rts panic
            - node card is not fully functional
            - link failure
            - network connection failed
            - machine check with hardware failure
            - hardware failure requiring replacement
            - power failure
            - fan failure
            - temperature critical
            - node crash
            - job terminated due to system failure
            
            KNOWN NORMAL BGL-LIKE PATTERNS:
            Classify as 0 when the message matches or is similar to:
            
            - instruction address: 0x...
            - data address: 0x...
            - core configuration register: 0x...
            - machine state register: 0x...
            - floating point status and control register: 0x...
            - force load/store alignment
            - program interrupt: illegal instruction
            - machine check: i-fetch
            - rts internal error
            - generating core.*
            - ciod: Error loading ... No such file or directory
            - ciod: Error loading ... Permission denied
            - ciod: Error loading ... Exec format error
            - ciod: Error loading ... invalid or missing program image
            - ciod: LOGIN chdir(...) failed: No such file or directory
            - invalid or missing program image
            - idoproxydb hit ASSERT condition
            - instruction cache parity error corrected
            - CE sym <*>, at <*>, mask <*>
            - ddr error(s) detected and corrected
            - torus receiver ... detected and corrected
            - tree receiver ... detected and corrected
            - any message containing "corrected"
            - any message containing "detected and corrected"
            - missing file/path without explicit system failure
            - permission denied without explicit system failure
            
            VERY IMPORTANT:
            - FATAL alone is not enough for anomaly.
            - ERROR alone is not enough for anomaly.
            - ASSERT alone is not enough for anomaly.
            - ADDRESS alone is not enough for anomaly.
            - INTERRUPT alone is not enough for anomaly.
            - But "data TLB error interrupt" is anomaly.
            - But "data storage interrupt" is anomaly.
            - But "failed to read message prefix on control stream" is anomaly.
            
            OUTPUT FORMAT:
            Return ONLY one JSON object:
            {"label":"0"} or {"label":"1"}
            No explanation, no extra text.
            """;

    private PromptGenerator() {
        /*
         * Utility class.
         */
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.ZERO_SHOT,
                        "BGL_ZERO_SHOT_V3",
                        BGL_ZERO_SHOT_PROMPT
                ),
                new PromptSpec(
                        PromptExperiment.RULE_BASED,
                        "BGL_RULE_BASED_V3",
                        BGL_RULE_BASED_PROMPT
                ),
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE,
                        "BGL_TEMPLATE_AWARE_V3",
                        BGL_TEMPLATE_AWARE_PROMPT
                )
        );
    }
}