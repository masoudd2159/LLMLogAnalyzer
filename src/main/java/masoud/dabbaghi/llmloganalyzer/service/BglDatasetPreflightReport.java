package masoud.dabbaghi.llmloganalyzer.service;

import java.time.Instant;

/** Dataset identity and parser coverage produced before any model inference. */
public record BglDatasetPreflightReport(
        String datasetPath,
        String sha256,
        long fileSizeBytes,
        long rawLines,
        long parsedLines,
        long parseErrors,
        long normalLabels,
        long anomalyLabels,
        Instant timestampUtc
) {
}
