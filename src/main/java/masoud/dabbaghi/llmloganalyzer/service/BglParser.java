package masoud.dabbaghi.llmloganalyzer.service;

import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglDecisionSource;
import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import masoud.dabbaghi.llmloganalyzer.evaluation.LogEvaluation;
import masoud.dabbaghi.llmloganalyzer.evaluation.LogEvaluationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@Slf4j
public class BglParser {

    static final Pattern LOG_PATTERN = Pattern.compile(
            "(?<label>\\S+)\\s+(?<timestamp>\\d+)\\s+(?<date>\\d{4}\\.\\d{2}\\.\\d{2})\\s+"
                    + "(?<location1>\\S+)\\s+(?<datetime>\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+)\\s+"
                    + "(?<location2>\\S+)\\s+(?<category>[A-Z]+)\\s+(?<component>[A-Z]+)\\s+"
                    + "(?<severity>[A-Z]+)\\s+(?<message>.*)"
    );

    private final CallModelAi callModelAi;
    private final LogEvaluationRepository repository;
    private final BglTemplateClassificationCache cache;
    private final BglTemplateValidationService validationService;

    @Value("${model.api.ollama.url}")
    private String ollamaApiUrl;
    @Value("${model.api.ollama.model-name}")
    private String ollamaModel;
    @Value("${bgl.location}")
    private String bglPath;
    @Value("${bgl.classification.template-cache.enabled:true}")
    private boolean cacheEnabled;
    @Value("${bgl.classification.template-guard.enabled:true}")
    private boolean guardEnabled;
    @Value("${bgl.classification.cache-only-validated-llm-results:true}")
    private boolean validateBeforeCache;
    @Value("${bgl.classification.template-key.include-metadata:true}")
    private boolean includeMetadata;

    public BglParser(
            CallModelAi callModelAi,
            LogEvaluationRepository repository,
            BglTemplateClassificationCache cache,
            BglTemplateValidationService validationService
    ) {
        this.callModelAi = callModelAi;
        this.repository = repository;
        this.cache = cache;
        this.validationService = validationService;
    }

    private static LogBglEntryDto parseLine(String line) {
        Matcher m = LOG_PATTERN.matcher(line);
        if (!m.matches()) {
            log.error("Failed to parse BGL line: {}", line);
            return null;
        }
        return new LogBglEntryDto()
                .setMainLog(line).setLabel(m.group("label")).setTimestamp(m.group("timestamp"))
                .setDate(m.group("date")).setLocation1(m.group("location1"))
                .setDatetime(m.group("datetime")).setLocation2(m.group("location2"))
                .setCategory(m.group("category")).setComponent(m.group("component"))
                .setSeverity(m.group("severity")).setMessage(m.group("message"));
    }

    private static ClassificationResult groundTruth(String label) {
        return "-".equals(label) ? ClassificationResult.NORMAL : ClassificationResult.ANOMALY;
    }

    private static ClassificationResult prediction(ModelClassificationResponse response) {
        if (response == null || !response.valid()) return ClassificationResult.INVALID;
        return switch (response.label()) {
            case "0" -> ClassificationResult.NORMAL;
            case "1" -> ClassificationResult.ANOMALY;
            default -> ClassificationResult.INVALID;
        };
    }

    public void logParser() throws IOException {
        AtomicInteger total = new AtomicInteger();
        AtomicInteger llm = new AtomicInteger();
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger llmHits = new AtomicInteger();
        AtomicInteger guardHits = new AtomicInteger();
        AtomicInteger directGuard = new AtomicInteger();
        AtomicInteger notCached = new AtomicInteger();
        PromptSpec prompt = PromptGenerator.finalBglPrompt();

        log.info("Starting BGL parsing: version={}, model={}, cache={}, guard={}, metadataKey={}",
                prompt.version(), ollamaModel, cacheEnabled, guardEnabled, includeMetadata);

        try (Stream<String> lines = Files.lines(Path.of(bglPath))) {
            lines.map(BglParser::parseLine).filter(Objects::nonNull).forEach(dto -> {
                FlowStats stats = classify(dto, prompt);
                if (stats.llmCalled) llm.incrementAndGet();
                if (stats.cacheHit) hits.incrementAndGet();
                if (stats.cacheFromLlm) llmHits.incrementAndGet();
                if (stats.cacheFromGuard) guardHits.incrementAndGet();
                if (stats.guardHit) directGuard.incrementAndGet();
                if (stats.notCached) notCached.incrementAndGet();
                int count = total.incrementAndGet();
                if (count % 1000 == 0) {
                    log.info("Processed={}, LLM={}, cache={} (LLM={}, guard={}), directGuard={}, cacheSize={}, notCached={}",
                            count, llm.get(), hits.get(), llmHits.get(), guardHits.get(),
                            directGuard.get(), cache.size(), notCached.get());
                }
            });
        }

        log.info("Finished. Total={}, LLM={}, cache={} (LLM={}, guard={}), directGuard={}, cacheSize={}, notCached={}",
                total.get(), llm.get(), hits.get(), llmHits.get(), guardHits.get(),
                directGuard.get(), cache.size(), notCached.get());
    }

    private FlowStats classify(LogBglEntryDto dto, PromptSpec prompt) {
        if (dto == null || dto.getMessage() == null) return FlowStats.EMPTY;

        ClassificationResult truth = groundTruth(dto.getLabel());
        BglTemplate template = BglTemplateExtractor.extract(dto, includeMetadata);
        String cacheKey = cacheKey(template, prompt);

        if (cacheEnabled) {
            Optional<BglCachedClassification> cached = cache.find(cacheKey);
            if (cached.isPresent()) {
                saveCached(dto, template, truth, cached.get(), prompt);
                BglDecisionSource source = cached.get().getOriginalDecisionSource();
                return new FlowStats(false, true, source == BglDecisionSource.LLM,
                        source == BglDecisionSource.TEMPLATE_GUARD, false, false);
            }
        }

        if (guardEnabled) {
            Optional<BglTemplateGuard.GuardResult> result = BglTemplateGuard.classify(
                    dto.getMessage(), template.normalizedMessage());
            if (result.isPresent()) {
                saveGuard(dto, template, cacheKey, truth, result.get(), prompt);
                return new FlowStats(false, false, false, false, true, false);
            }
        }

        boolean cached = saveLlm(dto, template, cacheKey, truth, prompt);
        return new FlowStats(true, false, false, false, false, !cached);
    }

    private void saveCached(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult truth,
            BglCachedClassification cached,
            PromptSpec prompt
    ) {
        ClassificationResult result = cached.getPrediction();
        repository.save(base(dto, template, truth, prompt)
                .aiResult(result).decisionSource(BglDecisionSource.TEMPLATE_CACHE)
                .cacheSource(cached.getOriginalDecisionSource())
                .matchedTemplatePattern(cached.getMatchedTemplatePattern())
                .rawModelOutput("TEMPLATE_CACHE:" + cached.getOriginalDecisionSource() + ":" + cached.getRawModelOutput())
                .validModelOutput(cached.isValidModelOutput()).correct(truth == result)
                .responseTimeMs(0L).cacheHit(true).cacheable(true)
                .validationStatus(cached.getValidationStatus())
                .validationReason(cached.getValidationReason()).build());
    }

    private void saveGuard(
            LogBglEntryDto dto,
            BglTemplate template,
            String cacheKey,
            ClassificationResult truth,
            BglTemplateGuard.GuardResult guard,
            PromptSpec prompt
    ) {
        String raw = "TEMPLATE_GUARD:" + guard.matchedTemplatePattern();
        String reason = "Deterministic template guard matched: " + guard.matchedTemplatePattern();
        ClassificationResult result = guard.prediction();

        repository.save(base(dto, template, truth, prompt)
                .aiResult(result).decisionSource(BglDecisionSource.TEMPLATE_GUARD)
                .cacheSource(null).matchedTemplatePattern(guard.matchedTemplatePattern())
                .rawModelOutput(raw).validModelOutput(true).correct(truth == result)
                .responseTimeMs(0L).cacheHit(false).cacheable(cacheEnabled)
                .validationStatus("APPROVED").validationReason(reason).build());

        if (cacheEnabled) {
            cache.putIfCacheable(new BglCachedClassification(
                    cacheKey, template.normalizedMessage(), result,
                    BglDecisionSource.TEMPLATE_GUARD, guard.matchedTemplatePattern(),
                    prompt.experiment(), prompt.version(), raw,
                    true, true, "APPROVED", reason));
        }
    }

    private boolean saveLlm(
            LogBglEntryDto dto,
            BglTemplate template,
            String cacheKey,
            ClassificationResult truth,
            PromptSpec prompt
    ) {
        long start = System.currentTimeMillis();
        ModelClassificationResponse response = callModelAi.classifyWithOllama(
                template.modelInput(), ollamaModel, prompt.prompt(), ollamaApiUrl);
        long responseTime = System.currentTimeMillis() - start;

        ClassificationResult result = prediction(response);
        boolean valid = response != null && response.valid();
        String raw = response == null ? "NULL_MODEL_RESPONSE" : response.rawOutput();
        BglTemplateValidationResult validation = validationService.validateForCache(
                dto.getMessage(), template.normalizedMessage(), result);
        boolean cacheable = cacheEnabled && valid && result != ClassificationResult.INVALID
                && (!validateBeforeCache || validation.approved());

        repository.save(base(dto, template, truth, prompt)
                .aiResult(result).decisionSource(BglDecisionSource.LLM).cacheSource(null)
                .matchedTemplatePattern(null).rawModelOutput(raw).validModelOutput(valid)
                .correct(result != ClassificationResult.INVALID && truth == result)
                .responseTimeMs(responseTime).cacheHit(false).cacheable(cacheable)
                .validationStatus(validation.status()).validationReason(validation.reason()).build());

        if (cacheable) {
            cache.putIfCacheable(new BglCachedClassification(
                    cacheKey, template.normalizedMessage(), result, BglDecisionSource.LLM,
                    null, prompt.experiment(), prompt.version(), raw,
                    valid, true, validation.status(), validation.reason()));
        }
        return cacheable;
    }

    private String cacheKey(BglTemplate template, PromptSpec prompt) {
        String model = ollamaModel == null || ollamaModel.isBlank() ? "UNKNOWN_MODEL" : ollamaModel.trim();
        return "prompt=" + prompt.version() + "|model=" + model + "|" + template.templateKey();
    }

    private LogEvaluation.LogEvaluationBuilder base(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult truth,
            PromptSpec prompt
    ) {
        return LogEvaluation.builder()
                .log(dto.getMainLog()).modelInput(template.modelInput()).datasetLabel(dto.getLabel())
                .realResult(truth).logType(LogType.BGL).aiModel(AiModel.OLLAMA)
                .templateKey(template.templateKey()).normalizedTemplate(template.normalizedMessage())
                .promptExperiment(prompt.experiment()).promptVersion(prompt.version())
                .prompt(prompt.prompt()).createdAt(LocalDateTime.now());
    }

    private record FlowStats(
            boolean llmCalled,
            boolean cacheHit,
            boolean cacheFromLlm,
            boolean cacheFromGuard,
            boolean guardHit,
            boolean notCached
    ) {
        private static final FlowStats EMPTY = new FlowStats(false, false, false, false, false, false);
    }
}
