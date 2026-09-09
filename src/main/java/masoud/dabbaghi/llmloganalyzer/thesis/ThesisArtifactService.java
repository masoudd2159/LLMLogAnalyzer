package masoud.dabbaghi.llmloganalyzer.thesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.evaluation.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.zip.GZIPOutputStream;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ThesisArtifactService {

    private final MongoTemplate mongoTemplate;
    private final BglExperimentRunRepository runRepository;
    private final EvaluationMetricsService metricsService;
    private final ObjectMapper objectMapper;

    @Value("${charts.output-dir:results}")
    private String outputDirectory;

    @Value("${thesis.preflight-report:}")
    private String preflightReport;

    public void exportCompletedRun(String runId) throws IOException {
        Path directory = Path.of(outputDirectory).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        BglExperimentRun run = requireRun(runId);
        writeJson(directory.resolve("bgl_experiment_runs.json"), run);
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new IllegalStateException("Run " + runId + " is not COMPLETED (status=" + run.getStatus() + ")");
        }
        validatePreflight(run);

        EvaluationMetrics all = metricsService.calculateForRun(LogType.BGL, AiModel.OLLAMA, runId);
        EvaluationMetrics direct = metricsService.calculateDirectDecisionsForRun(LogType.BGL, AiModel.OLLAMA, runId);
        Map<String, Object> consistency = validateConsistency(run, all);

        writeJson(directory.resolve("metrics_summary.json"), metricsSummary(all, run, consistency));
        writeJson(directory.resolve("direct_metrics_summary.json"), directMetricsSummary(runId, direct));
        writeJson(directory.resolve("decision_sources_summary.json"), decisionSources(all, run));
        writeJson(directory.resolve("evaluation_scope_summary.json"), evaluationScope(runId, run));

        DirectAnalysis directAnalysis = analyzeDirectLlm(runId);
        writeJson(directory.resolve("latency_summary.json"), directAnalysis.latency());
        writeJson(directory.resolve("token_usage_summary.json"), directAnalysis.tokens());
        writeJson(directory.resolve("confidence_summary.json"), directAnalysis.confidence());

        List<Map<String, Object>> topTemplates = writeTemplateAnalysis(runId, directory.resolve("template_analysis.csv"));
        writeValidationAnalysis(runId, directory.resolve("validation_analysis.csv"));
        writeJson(directory.resolve("error_summary.json"), errorSummary(runId, all, topTemplates));
        exportJsonLines(runId, directory.resolve("misclassified_records.jsonl.gz"), false);
        exportJsonLines(runId, directory.resolve("invalid_outputs.jsonl.gz"), true);
        writeJson(directory.resolve("run_environment.json"), runEnvironment(run));
    }

    public void writeChecksumsAndVerify(String runId) throws IOException {
        Path directory = Path.of(outputDirectory).toAbsolutePath().normalize();
        requireRun(runId);
        List<String> required = List.of(
                "bgl_experiment_runs.json", "metrics_summary.json", "direct_metrics_summary.json",
                "decision_sources_summary.json", "template_summary.json", "latency_summary.json",
                "token_usage_summary.json", "confidence_summary.json", "error_summary.json",
                "evaluation_scope_summary.json", "template_analysis.csv", "validation_analysis.csv",
                "misclassified_records.jsonl.gz", "invalid_outputs.jsonl.gz", "run_environment.json",
                "final_metrics.png", "final_confusion_matrix.png", "final_invalid_rate.png",
                "final_response_time.png", "final_decision_sources.png", "final_template_cache_size.png",
                "final_direct_metrics.png", "final_direct_confusion_matrix.png",
                "final_class_distribution.png", "final_error_breakdown.png",
                "final_llm_latency_percentiles.png", "final_top_error_templates.png"
        );
        // template_summary is a compact companion to the full CSV.
        if (!Files.exists(directory.resolve("template_summary.json"))) {
            List<Map<String, Object>> top = readTopTemplates(directory.resolve("template_analysis.csv"));
            Map<String, Object> summary = ordered();
            summary.put("runId", runId);
            summary.put("templateCount", countCsvRows(directory.resolve("template_analysis.csv")));
            summary.put("topErrorTemplates", top);
            writeJson(directory.resolve("template_summary.json"), summary);
        }
        for (String file : required) {
            if (!Files.isRegularFile(directory.resolve(file))) {
                throw new IllegalStateException("Required thesis artifact is missing: " + file);
            }
        }
        writeChecksums(directory);
    }

    private BglExperimentRun requireRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("RUN_ID is required for thesis artifact generation");
        }
        return runRepository.findById(runId.trim())
                .orElseThrow(() -> new IllegalStateException("Run is not present in this database: " + runId));
    }

    private void validatePreflight(BglExperimentRun run) throws IOException {
        Path reportPath = pathOrNull(preflightReport);
        if (!Files.isRegularFile(reportPath)) {
            throw new IllegalStateException("Dataset preflight report is missing: " + reportPath);
        }
        Map<String, Object> report = objectMapper.readValue(reportPath.toFile(), new TypeReference<>() { });
        Object sha = report.get("sha256");
        if (!Objects.equals(run.getDatasetSha256(), sha)) {
            throw new IllegalStateException("Dataset SHA differs from preflight: run=" + run.getDatasetSha256() + ", preflight=" + sha);
        }
        if (!Objects.equals(Path.of(run.getDatasetPath()).toAbsolutePath().normalize().toString(),
                Path.of(String.valueOf(report.get("datasetPath"))).toAbsolutePath().normalize().toString())) {
            throw new IllegalStateException("Dataset path differs from preflight report");
        }
    }

    private Map<String, Object> metricsSummary(EvaluationMetrics m, BglExperimentRun run,
                                                Map<String, Object> consistency) {
        Map<String, Object> out = ordered();
        out.put("runId", m.runId());
        out.put("experimentBatchId", run.getExperimentBatchId());
        out.put("total", m.total());
        out.put("validTotal", m.validTotal());
        out.put("invalidTotal", m.invalidTotal());
        out.put("validResponseRate", BinaryMetrics.divide(m.validTotal(), m.total()));
        out.put("invalidRate", m.invalidRate());
        out.put("normalGroundTruthCount", count(m.runId(), "realResult", ClassificationResult.NORMAL.name()));
        out.put("anomalyGroundTruthCount", count(m.runId(), "realResult", ClassificationResult.ANOMALY.name()));
        putBinary(out, m);
        out.put("averageResponseTimePerRecord", m.averageResponseTimeMs());
        out.put("averageDirectLlmResponseTime", m.averageLlmResponseTimeMs());
        out.put("processingDurationMs", m.processingDurationMs());
        out.put("throughputLinesPerSecond", m.throughputLinesPerSecond());
        out.put("zeroDenominatorBehavior", "All undefined ratios are reported as 0.0; INVALID predictions are excluded from TP/TN/FP/FN.");
        out.put("consistencyChecks", consistency);
        return out;
    }

    private Map<String, Object> directMetricsSummary(String runId, EvaluationMetrics m) {
        Map<String, Object> out = ordered();
        out.put("runId", runId);
        out.put("totalDirectDecisions", m.total());
        out.put("directValidTotal", m.validTotal());
        out.put("directInvalidTotal", m.invalidTotal());
        putBinary(out, m);
        out.put("directLlm", sourceMetricSummary(runId, BglDecisionSource.LLM));
        out.put("directRuleGuard", sourceMetricSummary(runId, BglDecisionSource.TEMPLATE_GUARD));
        out.put("zeroDenominatorBehavior", "All undefined ratios are reported as 0.0.");
        return out;
    }

    private Map<String, Object> sourceMetricSummary(String runId, BglDecisionSource source) {
        EvaluationMetrics m = calculateSubset(runId, source);
        Map<String, Object> out = ordered();
        out.put("total", m.total());
        out.put("validTotal", m.validTotal());
        out.put("invalidTotal", m.invalidTotal());
        putBinary(out, m);
        out.put("applicable", m.total() > 0);
        return out;
    }

    private EvaluationMetrics calculateSubset(String runId, BglDecisionSource source) {
        // Reuse the shared formulas while keeping this aggregation run/source scoped.
        Counts c = confusion(Query.query(new Criteria().andOperator(
                Criteria.where("runId").is(runId), Criteria.where("decisionSource").is(source.name()))));
        BinaryMetrics b = BinaryMetrics.from(c.tp, c.tn, c.fp, c.fn);
        return new EvaluationMetrics(null, "SOURCE", runId, source.name(), c.total, c.valid, c.invalid,
                c.tp, c.tn, c.fp, c.fn, b.accuracy(), b.precision(), b.recall(), b.f1(), b.specificity(),
                b.falsePositiveRate(), b.falseNegativeRate(), b.negativePredictiveValue(), b.balancedAccuracy(),
                b.mcc(), BinaryMetrics.divide(c.invalid, c.total), 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0);
    }

    private Map<String, Object> decisionSources(EvaluationMetrics m, BglExperimentRun run) {
        long noLlm = m.templateCacheDecisionCount() + m.templateGuardDecisionCount();
        Map<String, Object> out = ordered();
        out.put("runId", m.runId());
        out.put("directLlm", m.llmDecisionCount());
        out.put("templateCache", m.templateCacheDecisionCount());
        out.put("templateCacheDecisionCount", m.templateCacheDecisionCount());
        out.put("templateCacheFromLlm", m.templateCacheFromLlmDecisionCount());
        out.put("templateCacheFromGuard", m.templateCacheFromGuardDecisionCount());
        out.put("directGuard", m.templateGuardDecisionCount());
        out.put("cacheHitCount", m.cacheHitCount());
        out.put("cacheHitRate", BinaryMetrics.divide(m.cacheHitCount(), m.total()));
        out.put("noDirectLlmCount", noLlm);
        out.put("noDirectLlmRate", BinaryMetrics.divide(noLlm, m.total()));
        out.put("finalCacheSize", run.getFinalCacheSize());
        out.put("templateCacheSize", m.templateCacheSize());
        out.put("templateCacheSizeFromLlm", m.templateCacheSizeFromLlm());
        out.put("templateCacheSizeFromGuard", m.templateCacheSizeFromGuard());
        return out;
    }

    private Map<String, Object> evaluationScope(String runId, BglExperimentRun run) throws IOException {
        Map<String, Object> preflight = Files.isRegularFile(pathOrNull(preflightReport))
                ? objectMapper.readValue(pathOrNull(preflightReport).toFile(), new TypeReference<>() {}) : Map.of();
        long fullLines = asLong(preflight.get("rawLines"));
        Document range = recordRange(runId);
        Map<String, Object> out = ordered();
        out.put("datasetPath", run.getDatasetPath());
        out.put("datasetSha256", run.getDatasetSha256());
        out.put("fullBglLineCount", fullLines);
        out.put("evaluationScope", run.getEvaluationScope());
        out.put("officialThesisRun", run.isOfficialThesisRun());
        out.put("requestedRecordLimit", run.isRecordLimitExplicit() ? run.getMaxRecords() : null);
        out.put("processedRawRecords", run.getRawLineCount());
        out.put("parsedRecords", run.getParsedLineCount());
        out.put("parseErrors", run.getParseErrorCount());
        out.put("evaluationCoveragePercentage", run.getEvaluationCoveragePercentage());
        long normal = count(runId, "realResult", ClassificationResult.NORMAL.name());
        long anomaly = count(runId, "realResult", ClassificationResult.ANOMALY.name());
        out.put("normalCount", normal);
        out.put("anomalyCount", anomaly);
        out.put("normalPercentage", BinaryMetrics.divide(normal * 100.0, normal + anomaly));
        out.put("anomalyPercentage", BinaryMetrics.divide(anomaly * 100.0, normal + anomaly));
        out.put("firstRecordIndex", range == null ? null : range.get("first"));
        out.put("lastRecordIndex", range == null ? null : range.get("last"));
        out.put("developmentDataset", run.getDevelopmentDataset());
        out.put("developmentDataNote", run.getDevelopmentDataNote());
        out.put("observedTemplateCount", run.getObservedTemplateCount());
        out.put("templateLabelConflictCount", run.getTemplateLabelConflictCount());
        return out;
    }

    private DirectAnalysis analyzeDirectLlm(String runId) {
        List<Long> response = new ArrayList<>();
        List<Long> totalDuration = new ArrayList<>();
        List<Long> loadDuration = new ArrayList<>();
        List<Integer> promptTokens = new ArrayList<>();
        List<Integer> outputTokens = new ArrayList<>();
        List<ConfidencePoint> confidence = new ArrayList<>();
        long calls = 0;
        long callsWithTokenMetadata = 0;
        long validCalls = 0;
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("runId").is(runId), Criteria.where("decisionSource").is(BglDecisionSource.LLM.name())));
        query.fields().include("responseTimeMs").include("modelTotalDurationNanos").include("modelLoadDurationNanos")
                .include("promptTokenCount").include("outputTokenCount").include("modelConfidence")
                .include("aiResult").include("correct").include("validModelOutput");
        try (Stream<LogEvaluation> rows = mongoTemplate.stream(query, LogEvaluation.class)) {
            for (Iterator<LogEvaluation> iterator = rows.iterator(); iterator.hasNext();) {
                LogEvaluation row = iterator.next();
                calls++;
                if (row.getResponseTimeMs() != null && row.getResponseTimeMs() >= 0) response.add(row.getResponseTimeMs());
                if (row.getModelTotalDurationNanos() != null && row.getModelTotalDurationNanos() > 0) totalDuration.add(row.getModelTotalDurationNanos());
                if (row.getModelLoadDurationNanos() != null && row.getModelLoadDurationNanos() > 0) loadDuration.add(row.getModelLoadDurationNanos());
                if (row.getPromptTokenCount() != null) promptTokens.add(row.getPromptTokenCount());
                if (row.getOutputTokenCount() != null) outputTokens.add(row.getOutputTokenCount());
                if (row.getPromptTokenCount() != null && row.getOutputTokenCount() != null) {
                    callsWithTokenMetadata++;
                }
                if (Boolean.TRUE.equals(row.getValidModelOutput()) && row.getAiResult() != ClassificationResult.INVALID) {
                    validCalls++;
                    if (row.getModelConfidence() != null) {
                        confidence.add(new ConfidencePoint(row.getModelConfidence(), Boolean.TRUE.equals(row.getCorrect()), row.getAiResult()));
                    }
                }
            }
        }
        return new DirectAnalysis(latencyMap(calls, response, totalDuration, loadDuration),
                tokenMap(calls, callsWithTokenMetadata, promptTokens, outputTokens), confidenceMap(validCalls, confidence));
    }

    private Map<String, Object> latencyMap(long calls, List<Long> response, List<Long> totalNanos, List<Long> loadNanos) {
        Map<String, Object> out = ordered();
        out.put("directLlmCalls", calls);
        out.put("responseTimeMs", stats(response, Number::doubleValue));
        out.put("modelTotalDuration", durationStats(totalNanos));
        out.put("modelLoadDuration", durationStats(loadNanos));
        return out;
    }

    private Map<String, Object> tokenMap(long calls, long callsWithTokenMetadata, List<Integer> prompt, List<Integer> output) {
        Map<String, Object> out = ordered();
        out.put("directLlmCalls", calls);
        out.put("callsWithTokenMetadata", callsWithTokenMetadata);
        out.put("missingTokenMetadataCount", calls - callsWithTokenMetadata);
        out.put("totalPromptTokens", prompt.stream().mapToLong(Integer::longValue).sum());
        out.put("averagePromptTokens", mean(prompt, Number::doubleValue));
        out.put("medianPromptTokens", percentile(prompt, .50, Number::doubleValue));
        out.put("p95PromptTokens", percentile(prompt, .95, Number::doubleValue));
        out.put("totalOutputTokens", output.stream().mapToLong(Integer::longValue).sum());
        out.put("averageOutputTokens", mean(output, Number::doubleValue));
        out.put("medianOutputTokens", percentile(output, .50, Number::doubleValue));
        out.put("p95OutputTokens", percentile(output, .95, Number::doubleValue));
        long total = prompt.stream().mapToLong(Integer::longValue).sum() + output.stream().mapToLong(Integer::longValue).sum();
        out.put("totalTokens", total);
        out.put("averageTokensPerDirectLlmCall", BinaryMetrics.divide(total, calls));
        return out;
    }

    private Map<String, Object> confidenceMap(long validCalls, List<ConfidencePoint> points) {
        Map<String, Object> out = ordered();
        out.put("count", points.size());
        out.put("missingConfidenceCount", validCalls - points.size());
        out.put("overallMeanConfidence", points.stream().mapToDouble(ConfidencePoint::value).average().orElse(0));
        out.put("correctMeanConfidence", confidenceMean(points, true, null));
        out.put("incorrectMeanConfidence", confidenceMean(points, false, null));
        Map<String, Object> byPrediction = ordered();
        byPrediction.put("NORMAL", confidenceMean(points, null, ClassificationResult.NORMAL));
        byPrediction.put("ANOMALY", confidenceMean(points, null, ClassificationResult.ANOMALY));
        out.put("meanConfidenceByPrediction", byPrediction);
        List<Map<String, Object>> bins = new ArrayList<>();
        double[][] limits = {{0, .5}, {.5, .6}, {.6, .7}, {.7, .8}, {.8, .9}, {.9, 1.0000001}};
        for (double[] limit : limits) {
            List<ConfidencePoint> selected = points.stream().filter(p -> p.value >= limit[0] && p.value < limit[1]).toList();
            Map<String, Object> bin = ordered();
            bin.put("range", String.format(Locale.ROOT, "%.1f-%.1f", limit[0], Math.min(1, limit[1])));
            bin.put("count", selected.size());
            bin.put("empiricalAccuracy", BinaryMetrics.divide(selected.stream().filter(ConfidencePoint::correct).count(), selected.size()));
            bins.add(bin);
        }
        out.put("bins", bins);
        out.put("note", "Descriptive confidence analysis; calibration is not claimed.");
        return out;
    }

    private List<Map<String, Object>> writeTemplateAnalysis(String runId, Path target) throws IOException {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("runId", runId)),
                new Document("$group", templateGroup()),
                new Document("$addFields", new Document("labelConflict", new Document("$gt", List.of(new Document("$size", "$truthLabels"), 1)))
                        .append("errorRate", divideExpr("$errorCount", "$totalRecords"))
                        .append("accuracy", divideExpr("$correctCount", "$validOutputCount"))),
                new Document("$sort", new Document("errorCount", -1).append("totalRecords", -1))
        );
        List<Map<String, Object>> top = new ArrayList<>();
        long templates = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            String[] columns = templateColumns();
            writer.write(String.join(",", columns));
            writer.newLine();
            for (Document d : collection().aggregate(pipeline).allowDiskUse(true)) {
                templates++;
                Map<String, Object> row = templateRow(d);
                if (top.size() < 15) top.add(row);
                writeCsvRow(writer, columns, row);
            }
        }
        Map<String, Object> summary = ordered();
        summary.put("runId", runId);
        summary.put("templateCount", templates);
        summary.put("topErrorTemplates", top);
        writeJson(target.getParent().resolve("template_summary.json"), summary);
        return top;
    }

    private Document templateGroup() {
        return new Document("_id", "$templateKey")
                .append("templateKey", new Document("$first", "$templateKey"))
                .append("normalizedTemplate", new Document("$first", "$normalizedTemplate"))
                .append("bglCategory", new Document("$first", "$bglCategory"))
                .append("bglComponent", new Document("$first", "$bglComponent"))
                .append("bglSeverity", new Document("$first", "$bglSeverity"))
                .append("totalRecords", new Document("$sum", 1))
                .append("normalTruthCount", sumEq("$realResult", "NORMAL"))
                .append("anomalyTruthCount", sumEq("$realResult", "ANOMALY"))
                .append("TP", sumAnd(eq("$realResult", "ANOMALY"), eq("$aiResult", "ANOMALY")))
                .append("TN", sumAnd(eq("$realResult", "NORMAL"), eq("$aiResult", "NORMAL")))
                .append("FP", sumAnd(eq("$realResult", "NORMAL"), eq("$aiResult", "ANOMALY")))
                .append("FN", sumAnd(eq("$realResult", "ANOMALY"), eq("$aiResult", "NORMAL")))
                .append("errorCount", sumOr(
                        new Document("$and", List.of(eq("$realResult", "NORMAL"), eq("$aiResult", "ANOMALY"))),
                        new Document("$and", List.of(eq("$realResult", "ANOMALY"), eq("$aiResult", "NORMAL")))))
                .append("correctCount", sumEq("$correct", true))
                .append("directLlmCount", sumEq("$decisionSource", "LLM"))
                .append("directGuardCount", sumEq("$decisionSource", "TEMPLATE_GUARD"))
                .append("cacheHitCount", sumEq("$decisionSource", "TEMPLATE_CACHE"))
                .append("cacheFromLlmCount", sumAnd(eq("$decisionSource", "TEMPLATE_CACHE"), eq("$cacheSource", "LLM")))
                .append("cacheFromGuardCount", sumAnd(eq("$decisionSource", "TEMPLATE_CACHE"), eq("$cacheSource", "TEMPLATE_GUARD")))
                .append("cacheableCount", sumEq("$cacheable", true))
                .append("nonCacheableCount", sumEq("$cacheable", false))
                .append("validOutputCount", sumNe("$aiResult", "INVALID"))
                .append("invalidOutputCount", sumEq("$aiResult", "INVALID"))
                .append("truthLabels", new Document("$addToSet", "$realResult"));
    }

    private void writeValidationAnalysis(String runId, Path target) throws IOException {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("runId", runId)),
                new Document("$group", new Document("_id", new Document("validationStatus", "$validationStatus")
                        .append("validationReason", "$validationReason"))
                        .append("count", new Document("$sum", 1))
                        .append("cacheableCount", sumEq("$cacheable", true))
                        .append("nonCacheableCount", sumEq("$cacheable", false))
                        .append("correctCount", sumEq("$correct", true))
                        .append("incorrectCount", sumEq("$correct", false))),
                new Document("$sort", new Document("count", -1))
        );
        String[] columns = {"validationStatus", "validationReason", "count", "cacheableCount", "nonCacheableCount", "correctCount", "incorrectCount"};
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", columns)); writer.newLine();
            for (Document d : collection().aggregate(pipeline).allowDiskUse(true)) {
                Document id = d.get("_id", Document.class);
                Map<String, Object> row = ordered();
                row.put("validationStatus", id == null ? null : id.get("validationStatus"));
                row.put("validationReason", id == null ? null : id.get("validationReason"));
                for (int i = 2; i < columns.length; i++) row.put(columns[i], d.get(columns[i]));
                writeCsvRow(writer, columns, row);
            }
        }
    }

    private Map<String, Object> errorSummary(String runId, EvaluationMetrics all, List<Map<String, Object>> top) {
        long errors = all.falsePositive() + all.falseNegative();
        Map<String, Object> out = ordered();
        out.put("runId", runId);
        out.put("totalErrors", errors);
        out.put("FP", all.falsePositive()); out.put("FN", all.falseNegative());
        out.put("fpPercentageOfAllErrors", BinaryMetrics.divide(all.falsePositive() * 100.0, errors));
        out.put("fnPercentageOfAllErrors", BinaryMetrics.divide(all.falseNegative() * 100.0, errors));
        Document errorMatch = new Document("$or", List.of(
                new Document("realResult", "NORMAL").append("aiResult", "ANOMALY"),
                new Document("realResult", "ANOMALY").append("aiResult", "NORMAL")));
        out.put("byDecisionSource", groupCounts(runId, errorMatch, "decisionSource"));
        out.put("byCacheSource", groupCounts(runId, errorMatch, "cacheSource"));
        out.put("bySeverity", groupCounts(runId, errorMatch, "bglSeverity"));
        out.put("byComponent", groupCounts(runId, errorMatch, "bglComponent"));
        out.put("byCategory", groupCounts(runId, errorMatch, "bglCategory"));
        out.put("byModelCategoryForDirectLlm", groupCounts(runId,
                new Document("$and", List.of(errorMatch, new Document("decisionSource", "LLM"))), "modelCategory"));
        out.put("byValidationStatus", groupCounts(runId, errorMatch, "validationStatus"));
        out.put("topErrorTemplates", top);
        return out;
    }

    void exportJsonLines(String runId, Path target, boolean invalidOnly) throws IOException {
        Criteria criteria = invalidOnly
                ? new Criteria().andOperator(Criteria.where("runId").is(runId), Criteria.where("aiResult").is("INVALID"))
                : new Criteria().andOperator(Criteria.where("runId").is(runId), new Criteria().orOperator(
                new Criteria().andOperator(Criteria.where("realResult").is("NORMAL"), Criteria.where("aiResult").is("ANOMALY")),
                new Criteria().andOperator(Criteria.where("realResult").is("ANOMALY"), Criteria.where("aiResult").is("NORMAL"))));
        Query query = Query.query(criteria).with(org.springframework.data.domain.Sort.by("recordIndex"));
        String[] fields = invalidOnly
                ? new String[]{"recordIndex", "runId", "decisionSource", "templateKey", "rawModelOutput", "modelValidationError", "modelPrediction", "modelConfidence", "responseTimeMs"}
                : new String[]{"recordIndex", "runId", "realResult", "aiResult", "datasetLabel", "decisionSource", "cacheSource", "bglCategory", "bglComponent", "bglSeverity", "templateKey", "normalizedTemplate", "modelInput", "log", "matchedTemplatePattern", "validationStatus", "validationReason", "modelPrediction", "modelConfidence", "modelReason", "modelCategory", "responseTimeMs"};
        query.fields().include(fields);
        query.fields().exclude("_id");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(target));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8));
             Stream<Document> rows = mongoTemplate.stream(query, Document.class, mongoTemplate.getCollectionName(LogEvaluation.class))) {
            for (Iterator<Document> iterator = rows.iterator(); iterator.hasNext();) {
                writer.write(objectMapper.writeValueAsString(iterator.next()));
                writer.newLine();
            }
        }
    }

    private Map<String, Object> runEnvironment(BglExperimentRun run) {
        Map<String, Object> out = ordered();
        out.put("experimentBatchId", run.getExperimentBatchId()); out.put("runId", run.getRunId());
        out.put("databaseName", run.getDatabaseName()); out.put("method", methodLabel(run)); out.put("methodOrder", run.getMethodOrder());
        out.put("timestamp", Instant.now()); out.put("gitCommit", run.getGitCommit());
        out.put("gitBranch", command("git", "branch", "--show-current"));
        out.put("workingTreeClean", command("git", "status", "--porcelain").isBlank());
        out.put("datasetPath", run.getDatasetPath()); out.put("datasetSha256", run.getDatasetSha256());
        out.put("evaluationScope", run.getEvaluationScope()); out.put("officialThesisRun", run.isOfficialThesisRun());
        out.put("fullDatasetLineCount", run.getFullDatasetLineCount());
        out.put("requestedRecordLimit", run.isRecordLimitExplicit() ? run.getMaxRecords() : null);
        out.put("recordsToEvaluate", run.getMaxRecords());
        out.put("evaluationCoveragePercentage", run.getEvaluationCoveragePercentage());
        out.put("javaVersion", run.getJavaVersion()); out.put("mavenVersion", firstLine(command(mavenCommand(), "-version")));
        out.put("osName", run.getOsName()); out.put("osArchitecture", run.getOsArch());
        out.put("availableProcessors", run.getAvailableProcessors()); out.put("maxJvmMemoryBytes", run.getMaxJvmMemoryBytes());
        out.put("ollamaVersion", firstLine(command("ollama", "--version"))); out.put("modelName", run.getModelName()); out.put("modelDigest", run.getModelDigest());
        Map<String, Object> generation = ordered();
        generation.put("format", run.getFormat()); generation.put("thinking", run.isThinkingEnabled()); generation.put("temperature", run.getTemperature());
        generation.put("topP", run.getTopP()); generation.put("repeatPenalty", run.getRepeatPenalty()); generation.put("seed", run.getSeed());
        generation.put("numCtx", run.getNumCtx()); generation.put("numPredict", run.getNumPredict());
        out.put("modelGenerationSettings", generation);
        out.put("templateCacheEnabled", run.isTemplateCacheEnabled()); out.put("templateGuardEnabled", run.isTemplateGuardEnabled());
        Map<String, Object> timeouts = ordered(); timeouts.put("connectTimeoutMs", run.getConnectTimeoutMs()); timeouts.put("responseTimeoutMs", run.getResponseTimeoutMs());
        out.put("timeouts", timeouts);
        Map<String, Object> retry = ordered(); retry.put("maxAttempts", run.getMaxAttempts()); retry.put("initialBackoffMs", run.getRetryInitialBackoffMs()); retry.put("maxBackoffMs", run.getRetryMaxBackoffMs());
        out.put("retrySettings", retry); out.put("keepAlive", run.getKeepAlive());
        return out;
    }

    private Map<String, Object> validateConsistency(BglExperimentRun run, EvaluationMetrics m) throws IOException {
        Map<String, Object> checks = ordered();
        check(checks, "validPlusInvalidEqualsTotal", m.validTotal() + m.invalidTotal() == m.total());
        check(checks, "confusionMatrixEqualsValidTotal", m.truePositive() + m.trueNegative() + m.falsePositive() + m.falseNegative() == m.validTotal());
        check(checks, "decisionSourcesEqualTotal", m.llmDecisionCount() + m.templateCacheFromLlmDecisionCount()
                + m.templateCacheFromGuardDecisionCount() + m.templateGuardDecisionCount() == m.total());
        check(checks, "cacheSourcesEqualTemplateCache", m.templateCacheFromLlmDecisionCount()
                + m.templateCacheFromGuardDecisionCount() == m.templateCacheDecisionCount());
        check(checks, "directLlmCountMatchesRun", run.getDirectLlmCalls() == m.llmDecisionCount());
        check(checks, "cacheHitCountMatchesRun", run.getTotalCacheHits() == m.templateCacheDecisionCount());
        check(checks, "directGuardCountMatchesRun", run.getDirectGuardDecisions() == m.templateGuardDecisionCount());
        check(checks, "invalidCountMatchesRun", run.getInvalidModelOutputs() == m.invalidTotal());
        check(checks, "finalCacheSizeMatchesAggregation", run.getFinalCacheSize() == m.templateCacheSize());
        check(checks, "runPresentInSelectedDatabase", runRepository.existsById(run.getRunId()));
        check(checks, "databaseNameMatchesConnection", Objects.equals(run.getDatabaseName(), mongoTemplate.getDb().getName()));
        check(checks, "datasetShaPresent", nonBlank(run.getDatasetSha256()));
        check(checks, "modelDigestPresent", nonBlank(run.getModelDigest()));
        check(checks, "gitCommitPresent", nonBlank(run.getGitCommit()) && !"UNRECORDED".equalsIgnoreCase(run.getGitCommit()));
        Map<String, Object> preflight = objectMapper.readValue(pathOrNull(preflightReport).toFile(), new TypeReference<>() { });
        Document range = recordRange(run.getRunId());
        checks.putAll(BglScopeConsistencyValidator.validate(
                run,
                asLong(preflight.get("rawLines")),
                asLong(preflight.get("parsedLines")),
                asLong(preflight.get("parseErrors")),
                m.total(),
                range == null ? null : nullableLong(range.get("first")),
                range == null ? null : nullableLong(range.get("last"))));
        return checks;
    }

    private void check(Map<String, Object> checks, String name, boolean ok) {
        checks.put(name, ok);
        if (!ok) throw new IllegalStateException("Consistency check failed: " + name);
    }

    private Counts confusion(Query base) {
        Document filter = base.getQueryObject();
        long total = collection().countDocuments(filter);
        long invalid = collection().countDocuments(new Document("$and", List.of(filter, new Document("aiResult", "INVALID"))));
        long tp = pair(filter, "ANOMALY", "ANOMALY"); long tn = pair(filter, "NORMAL", "NORMAL");
        long fp = pair(filter, "NORMAL", "ANOMALY"); long fn = pair(filter, "ANOMALY", "NORMAL");
        return new Counts(total, total - invalid, invalid, tp, tn, fp, fn);
    }

    private long pair(Document base, String truth, String prediction) {
        Document query = new Document("$and", List.of(base, new Document("realResult", truth), new Document("aiResult", prediction)));
        return collection().countDocuments(query);
    }

    private long count(String runId, String field, Object value) {
        return collection().countDocuments(new Document("runId", runId).append(field, value));
    }

    private List<Map<String, Object>> groupCounts(String runId, Document extraMatch, String field) {
        Document match = new Document("$and", List.of(new Document("runId", runId), extraMatch));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : collection().aggregate(List.of(new Document("$match", match),
                new Document("$group", new Document("_id", "$" + field).append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1)))).allowDiskUse(true)) {
            Map<String, Object> row = ordered(); row.put("value", d.get("_id") == null ? "UNKNOWN" : d.get("_id")); row.put("count", d.get("count")); out.add(row);
        }
        return out;
    }

    private MongoCollection<Document> collection() {
        return mongoTemplate.getCollection(mongoTemplate.getCollectionName(LogEvaluation.class));
    }

    private Document recordRange(String runId) {
        return collection().aggregate(List.of(
                new Document("$match", new Document("runId", runId)),
                new Document("$group", new Document("_id", null)
                        .append("first", new Document("$min", "$recordIndex"))
                        .append("last", new Document("$max", "$recordIndex")))
        )).first();
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private void putBinary(Map<String, Object> out, EvaluationMetrics m) {
        out.put("TP", m.truePositive()); out.put("TN", m.trueNegative()); out.put("FP", m.falsePositive()); out.put("FN", m.falseNegative());
        out.put("accuracy", m.accuracy()); out.put("precision", m.precision()); out.put("recall", m.recall()); out.put("f1", m.f1Score());
        out.put("F1", m.f1Score());
        out.put("specificity", m.specificity()); out.put("falsePositiveRate", m.falsePositiveRate()); out.put("falseNegativeRate", m.falseNegativeRate());
        out.put("FPR", m.falsePositiveRate()); out.put("FNR", m.falseNegativeRate());
        out.put("negativePredictiveValue", m.negativePredictiveValue()); out.put("balancedAccuracy", m.balancedAccuracy()); out.put("MCC", m.mcc());
    }

    private Map<String, Object> stats(List<? extends Number> values, ToDoubleFunction<Number> fn) {
        Map<String, Object> out = ordered(); out.put("count", values.size());
        out.put("min", values.stream().mapToDouble(fn).min().orElse(0)); out.put("max", values.stream().mapToDouble(fn).max().orElse(0));
        out.put("mean", mean(values, fn)); out.put("median", percentile(values, .5, fn)); out.put("p50", percentile(values, .5, fn));
        out.put("p90", percentile(values, .9, fn)); out.put("p95", percentile(values, .95, fn)); out.put("p99", percentile(values, .99, fn));
        double mean = mean(values, fn); out.put("standardDeviation", values.isEmpty() ? 0 : Math.sqrt(values.stream().mapToDouble(v -> Math.pow(fn.applyAsDouble(v) - mean, 2)).average().orElse(0)));
        return out;
    }

    private Map<String, Object> durationStats(List<Long> nanos) {
        Map<String, Object> out = ordered(); out.put("originalUnit", "nanoseconds"); out.put("nanoseconds", stats(nanos, Number::doubleValue));
        out.put("milliseconds", stats(nanos, n -> n.doubleValue() / 1_000_000.0)); return out;
    }

    private double mean(List<? extends Number> values, ToDoubleFunction<Number> fn) { return values.stream().mapToDouble(fn).average().orElse(0); }
    private double percentile(List<? extends Number> values, double p, ToDoubleFunction<Number> fn) {
        if (values.isEmpty()) return 0;
        double[] sorted = values.stream().mapToDouble(fn).sorted().toArray();
        double index = p * (sorted.length - 1); int low = (int) Math.floor(index), high = (int) Math.ceil(index);
        return low == high ? sorted[low] : sorted[low] + (sorted[high] - sorted[low]) * (index - low);
    }

    private double confidenceMean(List<ConfidencePoint> points, Boolean correct, ClassificationResult prediction) {
        return points.stream().filter(p -> correct == null || p.correct == correct).filter(p -> prediction == null || p.prediction == prediction)
                .mapToDouble(ConfidencePoint::value).average().orElse(0);
    }

    private Document eq(Object a, Object b) { return new Document("$eq", List.of(a, b)); }
    private Document sumEq(Object a, Object b) { return new Document("$sum", new Document("$cond", List.of(eq(a, b), 1, 0))); }
    private Document sumNe(Object a, Object b) { return new Document("$sum", new Document("$cond", List.of(new Document("$ne", List.of(a, b)), 1, 0))); }
    private Document sumAnd(Document... conditions) { return new Document("$sum", new Document("$cond", List.of(new Document("$and", List.of(conditions)), 1, 0))); }
    private Document sumOr(Document... conditions) { return new Document("$sum", new Document("$cond", List.of(new Document("$or", List.of(conditions)), 1, 0))); }
    private Document divideExpr(String numerator, String denominator) { return new Document("$cond", List.of(new Document("$eq", List.of(denominator, 0)), 0, new Document("$divide", List.of(numerator, denominator)))); }

    private String[] templateColumns() { return new String[]{"templateKey", "normalizedTemplate", "bglCategory", "bglComponent", "bglSeverity", "totalRecords", "normalTruthCount", "anomalyTruthCount", "TP", "TN", "FP", "FN", "errorCount", "errorRate", "correctCount", "accuracy", "directLlmCount", "directGuardCount", "cacheHitCount", "cacheFromLlmCount", "cacheFromGuardCount", "cacheableCount", "nonCacheableCount", "validOutputCount", "invalidOutputCount", "labelConflict"}; }
    private Map<String, Object> templateRow(Document d) { Map<String, Object> row = ordered(); for (String c : templateColumns()) row.put(c, d.get(c)); return row; }
    private void writeCsvRow(BufferedWriter writer, String[] columns, Map<String, Object> row) throws IOException { for (int i=0;i<columns.length;i++){ if(i>0) writer.write(','); writer.write(csv(row.get(columns[i]))); } writer.newLine(); }
    private String csv(Object value) { String s = value == null ? "" : String.valueOf(value); return (s.contains(",") || s.contains("\"") || s.contains("\n")) ? "\"" + s.replace("\"", "\"\"") + "\"" : s; }

    private void writeJson(Path target, Object value) throws IOException { Files.createDirectories(target.toAbsolutePath().getParent()); objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value); }
    private void writeChecksums(Path directory) throws IOException {
        List<Path> files; try (var stream = Files.list(directory)) { files = stream.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("artifact_sha256.txt")).sorted().toList(); }
        try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve("artifact_sha256.txt"), StandardCharsets.UTF_8)) {
            for (Path file : files) writer.write(sha256(file) + "  " + file.getFileName() + System.lineSeparator());
        }
    }
    private String sha256(Path file) throws IOException { try { MessageDigest d=MessageDigest.getInstance("SHA-256"); try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536]; int n; while((n=in.read(b))>=0)d.update(b,0,n);} return HexFormat.of().formatHex(d.digest()); } catch(Exception e){throw new IOException(e);} }
    private Path pathOrNull(String value) { return value == null || value.isBlank() ? Path.of("__missing__") : Path.of(value).toAbsolutePath().normalize(); }
    private long asLong(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private String methodLabel(BglExperimentRun run) { return "HYBRID_GUARD_AND_LLM".equals(run.getClassificationMode()) ? "Hybrid Rule Guard + LLM" : "Prompt-only LLM"; }
    private String firstLine(String value) { return value == null ? "" : value.lines().findFirst().orElse(""); }
    private String mavenCommand() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "mvnw.cmd" : "./mvnw"; }
    private String command(String... command) { try { Process p=new ProcessBuilder(command).redirectErrorStream(true).start(); String value=new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim(); p.waitFor(); return value; } catch(Exception e){ return "UNAVAILABLE: " + e.getClass().getSimpleName(); } }
    private Map<String, Object> ordered() { return new LinkedHashMap<>(); }
    private long countCsvRows(Path path) throws IOException { try(var lines=Files.lines(path)){ return Math.max(0, lines.count()-1); } }
    private List<Map<String, Object>> readTopTemplates(Path path) { return List.of(); }

    private record Counts(long total,long valid,long invalid,long tp,long tn,long fp,long fn) {}
    private record ConfidencePoint(double value, boolean correct, ClassificationResult prediction) {}
    private record DirectAnalysis(Map<String,Object> latency, Map<String,Object> tokens, Map<String,Object> confidence) {}
}
