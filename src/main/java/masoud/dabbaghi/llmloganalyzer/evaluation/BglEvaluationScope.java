package masoud.dabbaghi.llmloganalyzer.evaluation;

/** Explicit dataset coverage mode persisted with every BGL experiment run. */
public enum BglEvaluationScope {
    FULL_DATASET,
    LIMITED_FIRST_N,

    /** Kept solely so historical MongoDB documents remain readable. */
    @Deprecated
    FIRST_N_RECORDS;

    public boolean isFullDataset() {
        return this == FULL_DATASET;
    }
}
