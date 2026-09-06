package masoud.dabbaghi.llmloganalyzer.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Reproducibility metadata and execution-level measurements for one BGL run.
 * Line-level predictions remain in {@link LogEvaluation} and reference this document by runId.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "bgl_experiment_runs")
@CompoundIndex(name = "completed_run_idx", def = "{'status': 1, 'finishedAt': -1}")
public class BglExperimentRun {

    @Id
    private String runId;

    private String status;
    private Instant startedAt;
    private Instant finishedAt;
    private String failureMessage;

    private String classificationMode;
    private PromptExperiment promptExperiment;
    private String promptVersion;
    private String prompt;

    private String datasetPath;
    private String datasetSha256;
    private long maxRecords;
    private String evaluationScope;
    private String developmentDataset;
    private String developmentDataNote;
    private long rawLineCount;
    private long parsedLineCount;
    private long parseErrorCount;

    private String modelName;
    private String modelVersion;
    private String modelDigest;
    private String format;
    private boolean thinkingEnabled;
    private double temperature;
    private double topP;
    private double repeatPenalty;
    private int seed;
    private int numCtx;
    private int numPredict;
    private long connectTimeoutMs;
    private long responseTimeoutMs;
    private int maxAttempts;

    private boolean templateCacheEnabled;
    private boolean templateGuardEnabled;
    private boolean validateBeforeCache;
    private boolean includeMetadataInTemplateKey;

    private long directLlmCalls;
    private long totalCacheHits;
    private long cacheHitsFromLlm;
    private long cacheHitsFromGuard;
    private long directGuardDecisions;
    private long nonCachedLlmResults;
    private long invalidModelOutputs;
    private long finalCacheSize;
    private long observedTemplateCount;
    private long templateLabelConflictCount;

    /** Dataset integrity hashing is measured separately from classification performance. */
    private long datasetHashDurationMs;

    /** Parsing, inference, cache/guard decisions, and MongoDB persistence. */
    private long processingDurationMs;
    private double throughputLinesPerSecond;

    private String gitCommit;
    private String javaVersion;
    private String osName;
    private String osArch;
    private int availableProcessors;
    private long maxJvmMemoryBytes;
}
