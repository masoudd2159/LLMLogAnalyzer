package masoud.dabbaghi.llmloganalyzer.evaluation;

public enum ClassificationResult {
    NORMAL,
    ANOMALY,

    /*
     * The model response was not a valid binary classification.
     * This must NOT be counted as NORMAL.
     */
    INVALID
}