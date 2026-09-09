package masoud.dabbaghi.llmloganalyzer.thesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ThesisComparisonService {
    private final ObjectMapper objectMapper;

    @Value("${thesis.hybrid-dir:}") private String hybridDirectory;
    @Value("${thesis.prompt-only-dir:}") private String promptOnlyDirectory;
    @Value("${thesis.comparison-dir:}") private String comparisonDirectory;
    @Value("${thesis.manifest-file:}") private String manifestFile;
    @Value("${thesis.preflight-report:}") private String preflightReport;
    @Value("${thesis.overall-started-at:}") private String overallStartedAt;

    public void compare() throws IOException {
        Path hybridDir = requiredDirectory(hybridDirectory, "Hybrid");
        Path promptDir = requiredDirectory(promptOnlyDirectory, "Prompt-only");
        Path output = Path.of(required(comparisonDirectory, "THESIS_COMPARISON_DIR")).toAbsolutePath().normalize();
        Files.createDirectories(output);

        BglExperimentRun hybridRun = readRun(hybridDir);
        BglExperimentRun promptRun = readRun(promptDir);
        validatePair(hybridRun, promptRun);

        Reports hybrid = readReports(hybridDir, hybridRun);
        Reports prompt = readReports(promptDir, promptRun);
        List<Map<String, Object>> rows = comparisonRows(hybrid, prompt);
        Map<String, Object> summary = ordered();
        summary.put("experimentBatchId", hybridRun.getExperimentBatchId());
        summary.put("hybridRunId", hybridRun.getRunId());
        summary.put("promptOnlyRunId", promptRun.getRunId());
        summary.put("metrics", rows);
        long llmReduction = asLong(prompt.decision.get("directLlm")) - asLong(hybrid.decision.get("directLlm"));
        long errorReduction = errors(prompt.metrics) - errors(hybrid.metrics);
        summary.put("llmCallReductionCount", llmReduction);
        long promptLlmCalls = asLong(prompt.decision.get("directLlm"));
        summary.put("llmCallReductionRate", promptLlmCalls == 0 ? null : divide(llmReduction, promptLlmCalls));
        summary.put("errorReductionCount", errorReduction);
        writeJson(output.resolve("comparison_summary.json"), summary);
        writeCsv(output.resolve("comparison_table.csv"), rows);

        chartMetrics(output.resolve("comparison_main_metrics.png"), "Main Metrics",
                List.of("accuracy", "precision", "recall", "f1"), hybrid.metrics, prompt.metrics);
        chartMetrics(output.resolve("comparison_direct_metrics.png"), "Direct-decision Metrics",
                List.of("accuracy", "precision", "recall", "f1"), hybrid.direct, prompt.direct);
        chartSingle(output.resolve("comparison_llm_calls.png"), "Direct LLM Calls", "Calls",
                asDouble(hybrid.decision.get("directLlm")), asDouble(prompt.decision.get("directLlm")), false);
        chartMetrics(output.resolve("comparison_cache_rates.png"), "Cache and No-direct-LLM Rates",
                List.of("cacheHitRate", "noDirectLlmRate"), hybrid.decision, prompt.decision);
        chartSingle(output.resolve("comparison_runtime.png"), "Observed Processing Duration", "Milliseconds",
                asDouble(hybrid.metrics.get("processingDurationMs")), asDouble(prompt.metrics.get("processingDurationMs")), false);
        chartSingle(output.resolve("comparison_throughput.png"), "Observed Throughput", "Lines/second",
                asDouble(hybrid.metrics.get("throughputLinesPerSecond")), asDouble(prompt.metrics.get("throughputLinesPerSecond")), false);
        writeChecksums(output);
        writeManifest(hybridRun, promptRun, hybridDir, promptDir, output);
    }

    private Reports readReports(Path dir, BglExperimentRun run) throws IOException {
        return new Reports(run, readMap(dir.resolve("metrics_summary.json")), readMap(dir.resolve("direct_metrics_summary.json")),
                readMap(dir.resolve("decision_sources_summary.json")), readMap(dir.resolve("latency_summary.json")),
                readMap(dir.resolve("token_usage_summary.json")));
    }

    private List<Map<String, Object>> comparisonRows(Reports h, Reports p) {
        List<Metric> metrics = List.of(
                m("total", h.metrics, p.metrics), m("validTotal", h.metrics, p.metrics), m("invalidTotal", h.metrics, p.metrics),
                rate("validResponseRate", h.metrics, p.metrics), rate("invalidRate", h.metrics, p.metrics),
                rate("accuracy", h.metrics, p.metrics), rate("precision", h.metrics, p.metrics), rate("recall", h.metrics, p.metrics), rate("f1", h.metrics, p.metrics),
                rate("specificity", h.metrics, p.metrics), rate("falsePositiveRate", h.metrics, p.metrics), rate("falseNegativeRate", h.metrics, p.metrics),
                rate("balancedAccuracy", h.metrics, p.metrics), rate("MCC", h.metrics, p.metrics),
                m("TP", h.metrics, p.metrics), m("TN", h.metrics, p.metrics), m("FP", h.metrics, p.metrics), m("FN", h.metrics, p.metrics),
                namedRate("directAccuracy", "accuracy", h.direct, p.direct), namedRate("directPrecision", "precision", h.direct, p.direct),
                namedRate("directRecall", "recall", h.direct, p.direct), namedRate("directF1", "f1", h.direct, p.direct),
                named("directLlmCalls", "directLlm", h.decision, p.decision), named("cacheHitCount", "cacheHitCount", h.decision, p.decision),
                namedRate("cacheHitRate", "cacheHitRate", h.decision, p.decision), named("noDirectLlmCount", "noDirectLlmCount", h.decision, p.decision),
                namedRate("noDirectLlmRate", "noDirectLlmRate", h.decision, p.decision), named("templateCacheSize", "templateCacheSize", h.decision, p.decision),
                runMetric("observedTemplateCount", h.run.getObservedTemplateCount(), p.run.getObservedTemplateCount()),
                runMetric("templateLabelConflictCount", h.run.getTemplateLabelConflictCount(), p.run.getTemplateLabelConflictCount()),
                named("averageResponseTimePerRecord", "averageResponseTimePerRecord", h.metrics, p.metrics),
                named("averageDirectLlmResponseTime", "averageDirectLlmResponseTime", h.metrics, p.metrics),
                nested("p50DirectLlmLatency", h.latency, p.latency, "responseTimeMs", "p50"),
                nested("p95DirectLlmLatency", h.latency, p.latency, "responseTimeMs", "p95"),
                nested("p99DirectLlmLatency", h.latency, p.latency, "responseTimeMs", "p99"),
                m("processingDurationMs", h.metrics, p.metrics), m("throughputLinesPerSecond", h.metrics, p.metrics),
                m("totalPromptTokens", h.tokens, p.tokens), m("totalOutputTokens", h.tokens, p.tokens), m("totalTokens", h.tokens, p.tokens)
        );
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Metric metric : metrics) {
            ComparisonDelta delta = calculateDelta(metric.hybrid, metric.prompt, metric.rate);
            Map<String, Object> row = ordered(); row.put("metric", metric.name); row.put("hybrid", metric.hybrid); row.put("promptOnly", metric.prompt);
            row.put("absoluteDelta", delta.absoluteDelta());
            row.put("relativeDifference", delta.relativeDifference());
            row.put("percentagePointDelta", delta.percentagePointDelta());
            rows.add(row);
        }
        return rows;
    }

    public void validatePair(BglExperimentRun h, BglExperimentRun p) {
        check("Hybrid status", "COMPLETED", h.getStatus()); check("Prompt-only status", "COMPLETED", p.getStatus());
        check("experimentBatchId", h.getExperimentBatchId(), p.getExperimentBatchId());
        check("Hybrid methodOrder", 1, h.getMethodOrder()); check("Prompt-only methodOrder", 2, p.getMethodOrder());
        check("Hybrid database", "hybrid", h.getDatabaseName()); check("Prompt-only database", "prompt_only", p.getDatabaseName());
        check("Hybrid classificationMode", "HYBRID_GUARD_AND_LLM", h.getClassificationMode());
        check("Prompt-only classificationMode", "PROMPT_ONLY_LLM", p.getClassificationMode());
        check("datasetPath", h.getDatasetPath(), p.getDatasetPath()); check("datasetSha256", h.getDatasetSha256(), p.getDatasetSha256()); check("maxRecords", h.getMaxRecords(), p.getMaxRecords());
        check("evaluationScope", h.getEvaluationScope(), p.getEvaluationScope()); check("modelName", h.getModelName(), p.getModelName());
        check("developmentDataset", h.getDevelopmentDataset(), p.getDevelopmentDataset()); check("developmentDataNote", h.getDevelopmentDataNote(), p.getDevelopmentDataNote());
        check("modelVersion", h.getModelVersion(), p.getModelVersion()); check("modelDigest", h.getModelDigest(), p.getModelDigest()); check("temperature", h.getTemperature(), p.getTemperature());
        check("topP", h.getTopP(), p.getTopP()); check("repeatPenalty", h.getRepeatPenalty(), p.getRepeatPenalty()); check("seed", h.getSeed(), p.getSeed());
        check("numCtx", h.getNumCtx(), p.getNumCtx()); check("numPredict", h.getNumPredict(), p.getNumPredict()); check("format", h.getFormat(), p.getFormat());
        check("thinkingEnabled", h.isThinkingEnabled(), p.isThinkingEnabled()); check("templateCacheEnabled", h.isTemplateCacheEnabled(), p.isTemplateCacheEnabled());
        check("validateBeforeCache", h.isValidateBeforeCache(), p.isValidateBeforeCache());
        check("includeMetadataInTemplateKey", h.isIncludeMetadataInTemplateKey(), p.isIncludeMetadataInTemplateKey()); check("gitCommit", h.getGitCommit(), p.getGitCommit());
        check("connectTimeoutMs", h.getConnectTimeoutMs(), p.getConnectTimeoutMs()); check("responseTimeoutMs", h.getResponseTimeoutMs(), p.getResponseTimeoutMs());
        check("maxAttempts", h.getMaxAttempts(), p.getMaxAttempts()); check("retryInitialBackoffMs", h.getRetryInitialBackoffMs(), p.getRetryInitialBackoffMs());
        check("retryMaxBackoffMs", h.getRetryMaxBackoffMs(), p.getRetryMaxBackoffMs()); check("keepAlive", h.getKeepAlive(), p.getKeepAlive());
        if (!h.isTemplateGuardEnabled() || p.isTemplateGuardEnabled()) throw new IllegalStateException("Template Guard flags do not match the two-method design");
    }

    public static ComparisonDelta calculateDelta(double hybrid, double promptOnly, boolean rate) {
        double absolute = hybrid - promptOnly;
        return new ComparisonDelta(absolute,
                rate || promptOnly == 0 ? null : absolute / promptOnly,
                rate ? absolute * 100.0 : null);
    }

    private void writeManifest(BglExperimentRun h, BglExperimentRun p, Path hDir, Path pDir, Path comparison) throws IOException {
        if (manifestFile == null || manifestFile.isBlank()) return;
        Map<String, Object> manifest = ordered(); manifest.put("experimentBatchId", h.getExperimentBatchId()); manifest.put("hybridRunId", h.getRunId());
        manifest.put("promptOnlyRunId", p.getRunId()); manifest.put("hybridDatabase", h.getDatabaseName()); manifest.put("promptOnlyDatabase", p.getDatabaseName());
        manifest.put("gitCommit", h.getGitCommit()); manifest.put("datasetSha256", h.getDatasetSha256()); manifest.put("modelDigest", h.getModelDigest());
        Map<String,Object> frozen=ordered(); frozen.put("modelName",h.getModelName()); frozen.put("temperature",h.getTemperature()); frozen.put("topP",h.getTopP());
        frozen.put("repeatPenalty",h.getRepeatPenalty()); frozen.put("seed",h.getSeed()); frozen.put("format",h.getFormat()); frozen.put("thinking",h.isThinkingEnabled());
        frozen.put("numCtx",h.getNumCtx()); frozen.put("numPredict",h.getNumPredict()); frozen.put("datasetPath",h.getDatasetPath()); frozen.put("maxRecords",h.getMaxRecords());
        frozen.put("evaluationScope",h.getEvaluationScope()); frozen.put("templateCacheEnabled",h.isTemplateCacheEnabled()); frozen.put("validateBeforeCache",h.isValidateBeforeCache());
        frozen.put("includeMetadataInTemplateKey",h.isIncludeMetadataInTemplateKey()); frozen.put("connectTimeoutMs",h.getConnectTimeoutMs()); frozen.put("responseTimeoutMs",h.getResponseTimeoutMs());
        frozen.put("maxAttempts",h.getMaxAttempts()); frozen.put("retryInitialBackoffMs",h.getRetryInitialBackoffMs()); frozen.put("retryMaxBackoffMs",h.getRetryMaxBackoffMs()); frozen.put("keepAlive",h.getKeepAlive());
        manifest.put("frozenSettings", frozen); manifest.put("hybridArtifactDirectory",hDir.toString()); manifest.put("promptOnlyArtifactDirectory",pDir.toString());
        manifest.put("comparisonArtifactDirectory",comparison.toString()); manifest.put("hybridChecksums",hDir.resolve("artifact_sha256.txt").toString());
        manifest.put("promptOnlyChecksums",pDir.resolve("artifact_sha256.txt").toString()); manifest.put("comparisonChecksums",comparison.resolve("artifact_sha256.txt").toString());
        manifest.put("preflightReport", preflightReport); manifest.put("overallStartedAt", overallStartedAt); manifest.put("overallFinishedAt", Instant.now()); manifest.put("overallStatus","COMPLETED");
        writeJson(Path.of(manifestFile).toAbsolutePath().normalize(), manifest);
    }

    private void chartMetrics(Path path, String title, List<String> names, Map<String,Object> h, Map<String,Object> p) throws IOException {
        DefaultCategoryDataset data=new DefaultCategoryDataset(); for(String n:names){data.addValue(asDouble(h.get(n)),"Hybrid",label(n));data.addValue(asDouble(p.get(n)),"Prompt-only",label(n));} saveChart(path,title,data,"Metric","Value",true);
    }
    private void chartSingle(Path path,String title,String axis,double h,double p,boolean ratio)throws IOException{DefaultCategoryDataset d=new DefaultCategoryDataset();d.addValue(h,"Value","Hybrid");d.addValue(p,"Value","Prompt-only");saveChart(path,title,d,"Method",axis,ratio);}
    private void saveChart(Path path,String title,DefaultCategoryDataset data,String category,String value,boolean ratio)throws IOException{
        JFreeChart chart=ChartFactory.createBarChart("Hybrid vs Prompt-only - "+title,category,value,data,PlotOrientation.VERTICAL,true,true,false);
        chart.setBackgroundPaint(Color.WHITE); chart.getCategoryPlot().setBackgroundPaint(Color.WHITE); chart.getCategoryPlot().setRangeGridlinePaint(Color.LIGHT_GRAY);
        BarRenderer renderer=(BarRenderer)chart.getCategoryPlot().getRenderer(); renderer.setSeriesPaint(0,Color.decode("#2563EB")); renderer.setSeriesPaint(1,Color.decode("#F97316"));
        if(ratio) chart.getCategoryPlot().getRangeAxis().setRange(0,1.05); ChartUtils.saveChartAsPNG(path.toFile(),chart,1400,820);
    }

    private BglExperimentRun readRun(Path dir)throws IOException{return objectMapper.readValue(dir.resolve("bgl_experiment_runs.json").toFile(),BglExperimentRun.class);}
    private Map<String,Object> readMap(Path p)throws IOException{return objectMapper.readValue(p.toFile(),new TypeReference<>(){});}
    private void writeJson(Path p,Object v)throws IOException{Files.createDirectories(p.getParent());objectMapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(),v);}
    private void writeCsv(Path p,List<Map<String,Object>> rows)throws IOException{try(BufferedWriter w=Files.newBufferedWriter(p,StandardCharsets.UTF_8)){w.write("metric,hybrid,promptOnly,absoluteDelta,relativeDifference,percentagePointDelta\n");for(Map<String,Object> r:rows){w.write(String.join(",",List.of(String.valueOf(r.get("metric")),str(r.get("hybrid")),str(r.get("promptOnly")),str(r.get("absoluteDelta")),str(r.get("relativeDifference")),str(r.get("percentagePointDelta")))));w.newLine();}}}
    private void writeChecksums(Path dir)throws IOException{List<Path> files;try(var s=Files.list(dir)){files=s.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals("artifact_sha256.txt")).sorted().toList();}try(BufferedWriter w=Files.newBufferedWriter(dir.resolve("artifact_sha256.txt"),StandardCharsets.UTF_8)){for(Path p:files)w.write(sha256(p)+"  "+p.getFileName()+System.lineSeparator());}}
    private String sha256(Path p)throws IOException{try{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}catch(Exception e){throw new IOException(e);}}
    private Path requiredDirectory(String value,String name){Path p=Path.of(required(value,name+" directory")).toAbsolutePath().normalize();if(!Files.isDirectory(p))throw new IllegalStateException(name+" artifact directory is missing: "+p);return p;}
    private String required(String value,String name){if(value==null||value.isBlank())throw new IllegalStateException(name+" is required");return value;}
    private void check(String name,Object a,Object b){if(!Objects.equals(a,b))throw new IllegalStateException("Comparison invariant mismatch for "+name+": hybrid="+a+", promptOnly="+b);}
    private Metric m(String n,Map<String,Object>h,Map<String,Object>p){return named(n,n,h,p);} private Metric rate(String n,Map<String,Object>h,Map<String,Object>p){return namedRate(n,n,h,p);}
    private Metric named(String n,String key,Map<String,Object>h,Map<String,Object>p){return new Metric(n,asDouble(h.get(key)),asDouble(p.get(key)),false);} private Metric namedRate(String n,String key,Map<String,Object>h,Map<String,Object>p){return new Metric(n,asDouble(h.get(key)),asDouble(p.get(key)),true);}
    private Metric nested(String n,Map<String,Object>h,Map<String,Object>p,String parent,String key){return new Metric(n,asDouble(map(h.get(parent)).get(key)),asDouble(map(p.get(parent)).get(key)),false);} private Metric runMetric(String n,double h,double p){return new Metric(n,h,p,false);}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object v){return v instanceof Map<?,?>m?(Map<String,Object>)m:Map.of();}
    private double asDouble(Object v){return v instanceof Number n?n.doubleValue():0;} private long asLong(Object v){return v instanceof Number n?n.longValue():0;} private long errors(Map<String,Object>m){return asLong(m.get("FP"))+asLong(m.get("FN"));}
    private double divide(double a,double b){return b==0?0:a/b;} private String str(Object v){return v==null?"":String.valueOf(v);} private String label(String s){return s.replaceAll("([a-z])([A-Z])","$1 $2");}
    private Map<String,Object> ordered(){return new LinkedHashMap<>();}
    public record ComparisonDelta(double absoluteDelta, Double relativeDifference, Double percentagePointDelta) {}
    private record Metric(String name,double hybrid,double prompt,boolean rate){} private record Reports(BglExperimentRun run,Map<String,Object>metrics,Map<String,Object>direct,Map<String,Object>decision,Map<String,Object>latency,Map<String,Object>tokens){}
}
