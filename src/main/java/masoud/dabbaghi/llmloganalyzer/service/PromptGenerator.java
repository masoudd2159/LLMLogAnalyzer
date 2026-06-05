package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_ZERO_SHOT_PROMPT = """
            You are an expert classifier for BGL (Blue Gene/L) HPC system logs.
            
            TASK:
            Given one BGL log entry, classify it as:
            
            0 = normal
            1 = anomaly
            
            IMPORTANT:
            The input provided to you does NOT contain the dataset ground-truth label.
            Do not infer from dataset labels or annotations.
            Classify only from the semantic meaning and real system impact of the log message.
            
            CLASSIFICATION POLICY:
            A log is ANOMALOUS only if it clearly indicates real system impact, such as:
            - unrecoverable hardware failure
            - uncorrected memory or communication failure
            - persistent hardware malfunction
            - node crash
            - job termination caused by system failure
            - corrupted execution
            - component failure requiring reset or replacement
            - fatal error that halts execution or affects computation
            
            A log is NORMAL if it describes:
            - informational events
            - routine status messages
            - diagnostics or monitoring
            - recoverable or corrected conditions
            - software or user configuration issues that do not clearly stop execution
            
            Severity words such as ERROR, FATAL, FAILURE, INVALID, MISSING, or WARNING
            are not sufficient by themselves.
            Use them only when the message also indicates real execution impact,
            crash, unrecoverable failure, corruption, or job termination.
            
            OUTPUT FORMAT:
            Return ONLY one JSON object:
            {"label":"0"} or {"label":"1"}
            No explanation, no extra text.
            """;

    public static final String BGL_RULE_BASED_PROMPT = """
            You are an expert classifier for BGL (Blue Gene/L) HPC system logs.
            
            TASK:
            Given one BGL log entry, classify it as:
            
            0 = normal
            1 = anomaly
            
            IMPORTANT:
            The input provided to you does NOT contain the dataset ground-truth label.
            Do not rely on any explicit label, alert marker, or dataset-provided annotation.
            Classify only based on the semantic meaning of the log message and its real system impact.
            
            ANOMALY RULES:
            Classify as 1 only if the log clearly indicates:
            - unrecoverable hardware failure
            - uncorrected memory or communication failure
            - persistent hardware malfunction
            - node crash
            - job termination caused by system failure
            - corrupted execution
            - component failure requiring reset or replacement
            - fatal error that halts execution or affects computation
            
            NORMAL RULES:
            Classify as 0 if the log describes:
            - corrected or recoverable errors
            - informational messages
            - startup/shutdown sequences
            - diagnostics or monitoring
            - bit sparing
            - ECC or DDR corrections
            - cache parity corrections
            - software or user configuration issues
            - missing files or invalid paths that do not clearly stop execution
            
            VERY IMPORTANT:
            Do NOT classify as anomaly only because the log contains:
            ERROR, FATAL, FAILURE, INVALID, MISSING, WARNING.
            
            Use impact-based reasoning:
            corrected/recovered/non-fatal => 0
            uncorrected/unrecoverable/crash/job-killing/corrupting => 1
            
            OUTPUT FORMAT:
            Return ONLY one JSON object:
            {"label":"0"} or {"label":"1"}
            No explanation, no extra text.
            """;

    public static final String BGL_TEMPLATE_AWARE_PROMPT = """
            You are an expert classifier for BGL (Blue Gene/L) HPC system logs.
            
            EXPERIMENT TYPE:
            This is a TEMPLATE_AWARE prompt.
            It uses known BGL-style message patterns as domain knowledge.
            Results from this prompt must be reported separately from ZERO_SHOT and RULE_BASED prompts.
            
            TASK:
            Given one BGL log entry, classify it as:
            
            0 = normal
            1 = anomaly
            
            IMPORTANT:
            The input provided to you does NOT contain the dataset ground-truth label.
            Do not rely on any explicit label, alert marker, or dataset-provided annotation.
            Classify only based on the semantic meaning of the log message and its real system impact.
            
            A log is ANOMALOUS only if it clearly indicates:
            - unrecoverable hardware failure
            - uncorrected memory or communication failure
            - persistent hardware malfunction
            - node crash
            - job termination caused by system failure
            - corrupted execution
            - component failure requiring reset or replacement
            - fatal error that halts execution or affects computation
            
            A log is NORMAL if it describes:
            - corrected or recoverable errors
            - informational messages
            - startup/shutdown sequences
            - diagnostics or monitoring
            - bit sparing
            - ECC or DDR corrections
            - cache parity corrections
            - software or user configuration issues
            - missing files or invalid paths that do not clearly stop execution
            - non-fatal template patterns
            
            KNOWN NORMAL BGL-LIKE PATTERNS:
            - instruction cache parity error corrected
            - CE sym <*>, at <*>, mask <*>
            - <*> ddr errors(s) detected and corrected on rank <*>
            - generating core.<*>
            - torus receiver <*>, detected and corrected
            - tree receiver <*>, in re-synch state
            - ciod: Error loading <*>: invalid or missing program image
            - ciod: LOGIN chdir(<*>) failed: No such file or directory
            - any log containing "detected and corrected"
            - any log containing "corrected"
            - missing or invalid path without clear execution impact
            
            KNOWN ANOMALOUS BGL-LIKE PATTERNS:
            - uncorrected ECC memory error detected
            - data TLB error interrupt
            - data storage interrupt
            - kernel panic
            - rts panic!
            - node crash due to hardware failure
            - persistent communication failure causing job termination
            - any clearly uncorrectable fault
            
            VERY IMPORTANT:
            Severity words such as ERROR, FATAL, FAILURE, INVALID, MISSING, or WARNING
            are not sufficient by themselves.
            Use them only when the message also indicates real execution impact,
            crash, unrecoverable failure, corruption, or job termination.
            
            Use impact-based reasoning:
            corrected/recovered/non-fatal => 0
            uncorrected/unrecoverable/crash/job-killing/corrupting => 1
            
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
        return List.of(new PromptSpec(PromptExperiment.ZERO_SHOT, "BGL_ZERO_SHOT_V1", BGL_ZERO_SHOT_PROMPT), new PromptSpec(PromptExperiment.RULE_BASED, "BGL_RULE_BASED_V1", BGL_RULE_BASED_PROMPT), new PromptSpec(PromptExperiment.TEMPLATE_AWARE, "BGL_TEMPLATE_AWARE_V1", BGL_TEMPLATE_AWARE_PROMPT));
    }
}