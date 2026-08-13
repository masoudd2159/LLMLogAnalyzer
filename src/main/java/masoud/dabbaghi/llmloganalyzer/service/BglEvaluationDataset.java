package masoud.dabbaghi.llmloganalyzer.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Builds a reproducible logical BGL evaluation set by excluding exact development lines.
 * The source file is not modified or copied. Matching is performed on the complete raw line.
 */
public final class BglEvaluationDataset {

    private static final byte[] LINE_SEPARATOR = new byte[]{'\n'};

    private BglEvaluationDataset() {
    }

    public static ExclusionPlan loadExclusion(boolean enabled, String configuredPath) throws IOException {
        if (!enabled) {
            return ExclusionPlan.disabled();
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException(
                    "bgl.evaluation.exclusion.location is required when exclusion is enabled."
            );
        }

        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("BGL exclusion file does not exist: " + path);
        }

        Map<String, Integer> requiredOccurrences = new HashMap<>();
        long lineCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                requiredOccurrences.merge(line, 1, Integer::sum);
                lineCount++;
            }
        }

        if (lineCount == 0) {
            throw new IllegalArgumentException("BGL exclusion file is empty: " + path);
        }

        return new ExclusionPlan(
                true,
                path,
                sha256(path),
                Collections.unmodifiableMap(requiredOccurrences),
                lineCount
        );
    }

    /**
     * Validates exact multiplicity before any LLM call and hashes the resulting logical holdout.
     * The holdout digest uses UTF-8 line bytes followed by LF for every included logical line.
     */
    public static PreflightResult preflight(Path datasetPath, ExclusionPlan plan) throws IOException {
        Path normalizedDataset = datasetPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedDataset)) {
            throw new IllegalArgumentException("BGL dataset does not exist: " + normalizedDataset);
        }
        if (plan.enabled() && normalizedDataset.equals(plan.path())) {
            throw new IllegalArgumentException("BGL dataset and exclusion file must be different files.");
        }

        MessageDigest evaluationDigest = newSha256();
        Map<String, Integer> observedOccurrences = new HashMap<>();
        long sourceLineCount = 0;
        long evaluationLineCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(normalizedDataset, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                sourceLineCount++;
                if (plan.requiredOccurrences().containsKey(line)) {
                    observedOccurrences.merge(line, 1, Integer::sum);
                    continue;
                }

                updateLogicalLineDigest(evaluationDigest, line);
                evaluationLineCount++;
            }
        }

        if (plan.enabled() && !observedOccurrences.equals(plan.requiredOccurrences())) {
            throw new IllegalStateException(describeMismatch(plan, observedOccurrences));
        }

        long excludedLineCount = sourceLineCount - evaluationLineCount;
        return new PreflightResult(
                sourceLineCount,
                evaluationLineCount,
                excludedLineCount,
                HexFormat.of().formatHex(evaluationDigest.digest())
        );
    }

    public static LineExcluder newLineExcluder(ExclusionPlan plan) {
        return new LineExcluder(plan.requiredOccurrences());
    }

    private static String describeMismatch(
            ExclusionPlan plan,
            Map<String, Integer> observedOccurrences
    ) {
        long missing = 0;
        long extra = 0;
        for (Map.Entry<String, Integer> required : plan.requiredOccurrences().entrySet()) {
            int observed = observedOccurrences.getOrDefault(required.getKey(), 0);
            missing += Math.max(0, required.getValue() - observed);
            extra += Math.max(0, observed - required.getValue());
        }
        return "BGL exclusion does not match the source dataset exactly: missing="
                + missing + ", extraDuplicateMatches=" + extra
                + ", exclusionFile=" + plan.path();
    }

    private static void updateLogicalLineDigest(MessageDigest digest, String line) {
        digest.update(line.getBytes(StandardCharsets.UTF_8));
        digest.update(LINE_SEPARATOR);
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = newSha256();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record ExclusionPlan(
            boolean enabled,
            Path path,
            String sha256,
            Map<String, Integer> requiredOccurrences,
            long lineCount
    ) {
        private static ExclusionPlan disabled() {
            return new ExclusionPlan(false, null, null, Map.of(), 0);
        }
    }

    public record PreflightResult(
            long sourceLineCount,
            long evaluationLineCount,
            long excludedLineCount,
            String evaluationSha256
    ) {
    }

    public static final class LineExcluder {

        private final Map<String, Integer> remainingOccurrences;
        private long excludedLineCount;

        private LineExcluder(Map<String, Integer> requiredOccurrences) {
            this.remainingOccurrences = new HashMap<>(requiredOccurrences);
        }

        public boolean shouldExclude(String line) {
            Integer remaining = remainingOccurrences.get(line);
            if (remaining == null || remaining == 0) {
                return false;
            }

            if (remaining == 1) {
                remainingOccurrences.remove(line);
            } else {
                remainingOccurrences.put(line, remaining - 1);
            }
            excludedLineCount++;
            return true;
        }

        public long excludedLineCount() {
            return excludedLineCount;
        }

        public void verifyComplete() {
            if (!remainingOccurrences.isEmpty()) {
                long missing = remainingOccurrences.values().stream()
                        .mapToLong(Integer::longValue)
                        .sum();
                throw new IllegalStateException(
                        "BGL processing finished before all development lines were excluded: missing="
                                + missing
                );
            }
        }
    }
}
