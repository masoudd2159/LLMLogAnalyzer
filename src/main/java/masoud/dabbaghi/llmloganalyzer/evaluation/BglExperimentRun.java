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

import java.time.LocalDateTime;

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
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String failureMessage;

    private String classificationMode;
    private PromptExperiment promptExperiment;
    private String promptVersion;
    private String prompt;

    private String datasetPath;
    private String datasetSha256;
    private boolean developmentExclusionEnabled;
    private String developmentExclusionPath;
    private String developmentExclusionSha256;
    private long configuredDevelopmentLineCount;
    private long excludedDevelopmentLineCount;
    private long evaluationInputLineCount;
    private String evaluationDatasetSha256;
    private long rawLineCount;
    private long parsedLineCount;
    private long parseErrorCount;

    private String modelName;
    private String modelDigest;
    private double temperature;
    private double topP;
    private double repeatPenalty;
    private int seed;
    private int numCtx;
    private int numPredict;

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
    private long finalCacheSize;

    /** Dataset integrity hashing is measured separately from classification performance. */
    private long datasetHashDurationMs;

    /** Exact exclusion validation and logical holdout hashing occur before timed processing. */
    private long exclusionPreflightDurationMs;

    /** Parsing, inference, cache/guard decisions, and MongoDB persistence. */
    private long processingDurationMs;
    private double throughputLinesPerSecond;

    private String gitCommit;
    private boolean gitWorkingTreeDirty;
    private String javaVersion;
    private String osName;
    private String osArch;
    private int availableProcessors;
    private long maxJvmMemoryBytes;
}
