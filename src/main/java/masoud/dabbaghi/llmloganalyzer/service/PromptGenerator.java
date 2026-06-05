package masoud.dabbaghi.llmloganalyzer.service;

public class PromptGenerator {
    public static final String BGL_PROMPT = """
            You are an expert classifier for BGL (Blue Gene/L) HPC system logs.
            
            TASK:
            Given a single raw BGL log line, classify it as:
            
            0 = normal
            1 = anomaly
            
            IMPORTANT:
            The input may contain metadata fields such as timestamp, node ID, component, severity, or dataset annotations.
            Do NOT rely on any explicit label, alert marker, or dataset-provided annotation.
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
            - missing files or invalid paths that do not stop execution
            - non-fatal template patterns
            
            KNOWN NORMAL PATTERNS:
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
            - missing or invalid path without execution impact
            
            KNOWN ANOMALOUS PATTERNS:
            - uncorrected ECC memory error detected
            - data TLB error interrupt
            - data storage interrupt
            - kernel panic
            - rts panic!
            - node crash due to hardware failure
            - persistent communication failure causing job termination
            - any clearly uncorrectable fault
            
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


    private PromptGenerator() {
        /* This utility class should not be instantiated */
    }
}

