package masoud.dabbaghi.llmloganalyzer.service;

public class PromptGenerator {
    public static final String BGL_PROMPT = """
            You are an expert HPC (High Performance Computing) system log analyzer.
            
            TASK:
            Classify the following BGL log message as:
            
            0 = normal
            1 = anomaly
            
            DEFINITION OF ANOMALY:
            A log is anomalous ONLY if it indicates:
            
            unrecoverable hardware failure
            persistent system malfunction
            node crash
            corrupted execution
            uncorrected memory or communication failure
            system instability affecting execution
            IMPORTANT RULES:
            
            Do NOT classify logs as anomalous based only on keywords such as:
            ERROR, FATAL, FAILURE, WARNING, INVALID, MISSING, or NO SUCH FILE.
            
            In BGL systems, some severe-looking logs are part of expected operational behavior and should still be considered normal.
            
            Corrected, recoverable, temporary, informational, startup-related, or operational events are usually NORMAL.
            
            Focus on actual operational impact rather than emotional or severe wording.
            
            If the log does not clearly indicate a real system failure, classify it as NORMAL.
            
            FEW-SHOT EXAMPLES:
            
            Example:
            instruction cache parity error corrected
            Answer: 0
            
            Example:
            temporary application startup loading issue
            Answer: 0
            
            Example:
            uncorrected ECC memory failure detected
            Answer: 1
            
            Example:
            persistent node hardware malfunction
            Answer: 1
            
            OUTPUT RULES:
            
            Return ONLY one character:
            0
            or
            1
            No explanation
            No extra text
            No punctuation
            No whitespace
            """;


    private PromptGenerator() {
        /* This utility class should not be instantiated */
    }
}

