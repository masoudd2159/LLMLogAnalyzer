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
            "(?<label>\\S+)\\s+" +
                    "(?<timestamp>\\d+)\\s+" +
                    "(?<date>\\d{4}\\.\\d{2}\\.\\d{2})\\s+" +
                    "(?<location1>\\S+)\\s+" +
                    "(?<datetime>\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+)\\s+" +
                    "(?<location2>\\S+)\\s+" +
                    "(?<category>[A-Z]+)\\s+" +
                    "(?<component>[A-Z]+)\\s+" +
                    "(?<severity>[A-Z]+)\\s+" +
                    "(?<message>.*)"
    );

    private final CallModelAi callModelAi;
    private final LogEvaluationRepository logEvaluationRepository;
    private final BglTemplateClassificationCache templateCache;
    private final BglTemplateValidationService validationService;

    @Value("${model.api.ollama.url}")
    private String ollamaApiUrl;

    @Value("${model.api.ollama.model-name}")
    private String ollamaModel;

    @Value("${bgl.location}")
    private String bglPath;

    @Value("${bgl.classification.template-cache.enabled:true}")
    private boolean templateCacheEnabled;

    @Value("${bgl.classification.template-guard.enabled:true}")
    private boolean templateGuardEnabled;

    @Value("${bgl.classification.cache-only-validated-llm-results:true}")
    private boolean cacheOnlyValidatedLlmResults;

    @Value("${bgl.classification.template-key.include-metadata:false}")
    private boolean includeMetadataInTemplateKey;

    public BglParser(
            CallModelAi callModelAi,
            LogEvaluationRepository logEvaluationRepository,
            BglTemplateClassificationCache templateCache,
            BglTemplateValidationService validationService
    ) {
        this.callModelAi = callModelAi;
        this.logEvaluationRepository = logEvaluationRepository;
        this.templateCache = templateCache;
        this.validationService = validationService;
    }

    private static LogBglEntryDto parseLine(String line) {
        Matcher matcher = LOG_PATTERN.matcher(line);

        if (!matcher.matches()) {
            log.error("Failed to parse BGL line: {}", line);
            return null;
        }

        return new LogBglEntryDto()
                .setMainLog(line)
                .setLabel(matcher.group("label"))
                .setTimestamp(matcher.group("timestamp"))
                .setDate(matcher.group("date"))
                .setLocation1(matcher.group("location1"))
                .setDatetime(matcher.group("datetime"))
                .setLocation2(matcher.group("location2"))
                .setCategory(matcher.group("category"))
                .setComponent(matcher.group("component"))
                .setSeverity(matcher.group("severity"))
                .setMessage(matcher.group("message"));
    }

    private static ClassificationResult toGroundTruth(String datasetLabel) {
        return "-".equals(datasetLabel)
                ? ClassificationResult.NORMAL
                : ClassificationResult.ANOMALY;
    }

    private static ClassificationResult toPrediction(ModelClassificationResponse response) {
        if (response == null || !response.valid()) {
            return ClassificationResult.INVALID;
        }

        return switch (response.label()) {
            case "0" -> ClassificationResult.NORMAL;
            case "1" -> ClassificationResult.ANOMALY;
            default -> ClassificationResult.INVALID;
        };
    }

    public void logParser() throws IOException {
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger llmCallCount = new AtomicInteger(0);
        AtomicInteger cacheHitCount = new AtomicInteger(0);
        AtomicInteger cacheHitFromLlmCount = new AtomicInteger(0);
        AtomicInteger cacheHitFromGuardCount = new AtomicInteger(0);
        AtomicInteger guardHitCount = new AtomicInteger(0);
        AtomicInteger notCachedCount = new AtomicInteger(0);

        PromptSpec finalPrompt = PromptGenerator.finalBglPrompt();

        log.info(
                "Starting BGL parsing with template-cache hybrid method: experiment={}, version={}, cacheEnabled={}, guardEnabled={}, includeMetadataInTemplateKey={}",
                finalPrompt.experiment(),
                finalPrompt.version(),
                templateCacheEnabled,
                templateGuardEnabled,
                includeMetadataInTemplateKey
        );

        try (Stream<String> lines = Files.lines(Path.of(bglPath))) {
            lines.map(BglParser::parseLine)
                    .filter(Objects::nonNull)
                    .forEach(dto -> {
                        ClassificationFlowStats stats = classifyAndSaveWithTemplateCache(dto, finalPrompt);

                        if (stats.llmCalled()) {
                            llmCallCount.incrementAndGet();
                        }
                        if (stats.cacheHit()) {
                            cacheHitCount.incrementAndGet();
                        }
                        if (stats.cacheHitFromLlm()) {
                            cacheHitFromLlmCount.incrementAndGet();
                        }
                        if (stats.cacheHitFromGuard()) {
                            cacheHitFromGuardCount.incrementAndGet();
                        }
                        if (stats.guardHit()) {
                            guardHitCount.incrementAndGet();
                        }
                        if (stats.notCached()) {
                            notCachedCount.incrementAndGet();
                        }

                        int count = processedCount.incrementAndGet();
                        if (count % 100 == 0) {
                            log.info(
                                    "Processed BGL lines: {}, LLM calls: {}, cache hits: {} (from LLM: {}, from Guard: {}), guard hits: {}, cache size: {}, not cached: {}",
                                    count,
                                    llmCallCount.get(),
                                    cacheHitCount.get(),
                                    cacheHitFromLlmCount.get(),
                                    cacheHitFromGuardCount.get(),
                                    guardHitCount.get(),
                                    templateCache.size(),
                                    notCachedCount.get()
                            );
                        }
                    });
        }

        log.info(
                "Finished BGL parsing. Total={}, LLM calls={}, cache hits={} (from LLM={}, from Guard={}), guard hits={}, cache size={}, not cached={}",
                processedCount.get(),
                llmCallCount.get(),
                cacheHitCount.get(),
                cacheHitFromLlmCount.get(),
                cacheHitFromGuardCount.get(),
                guardHitCount.get(),
                templateCache.size(),
                notCachedCount.get()
        );
    }

    private ClassificationFlowStats classifyAndSaveWithTemplateCache(
            LogBglEntryDto dto,
            PromptSpec promptSpec
    ) {
        if (dto == null || dto.getMessage() == null) {
            log.warn("Skipping invalid BGL log entry: {}", dto);
            return ClassificationFlowStats.empty();
        }

        ClassificationResult realResult = toGroundTruth(dto.getLabel());
        BglTemplate template = BglTemplateExtractor.extract(dto, includeMetadataInTemplateKey);

        if (templateCacheEnabled) {
            Optional<BglCachedClassification> cached = templateCache.find(template.templateKey());
            if (cached.isPresent()) {
                BglCachedClassification cachedClassification = cached.get();
                saveFromCache(dto, template, realResult, cachedClassification, promptSpec);

                BglDecisionSource cacheSource = cachedClassification.getOriginalDecisionSource();
                return new ClassificationFlowStats(
                        false,
                        true,
                        cacheSource == BglDecisionSource.LLM,
                        cacheSource == BglDecisionSource.TEMPLATE_GUARD,
                        false,
                        false
                );
            }
        }

        if (templateGuardEnabled) {
            Optional<BglTemplateGuard.GuardResult> guardResult = BglTemplateGuard.classify(
                    dto.getMessage() + " " + template.normalizedMessage()
            );
            if (guardResult.isPresent()) {
                saveFromGuard(dto, template, realResult, guardResult.get(), promptSpec);
                return new ClassificationFlowStats(false, false, false, false, true, false);
            }
        }

        boolean cachedAfterLlm = classifyAndSaveWithLlm(dto, template, realResult, promptSpec);
        return new ClassificationFlowStats(true, false, false, false, false, !cachedAfterLlm);
    }

    private void saveFromCache(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult realResult,
            BglCachedClassification cached,
            PromptSpec promptSpec
    ) {
        ClassificationResult prediction = cached.getPrediction();
        boolean correct = prediction != ClassificationResult.INVALID && realResult == prediction;

        LogEvaluation evaluation = baseEvaluation(dto, template, realResult, promptSpec)
                .aiResult(prediction)
                .decisionSource(BglDecisionSource.TEMPLATE_CACHE)
                .cacheSource(cached.getOriginalDecisionSource())
                .matchedTemplatePattern(cached.getMatchedTemplatePattern())
                .rawModelOutput("TEMPLATE_CACHE:" + cached.getOriginalDecisionSource() + ":" + cached.getRawModelOutput())
                .validModelOutput(cached.isValidModelOutput())
                .correct(correct)
                .responseTimeMs(0L)
                .cacheHit(true)
                .cacheable(true)
                .validationStatus(cached.getValidationStatus())
                .validationReason(cached.getValidationReason())
                .build();

        logEvaluationRepository.save(evaluation);
    }

    private void saveFromGuard(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult realResult,
            BglTemplateGuard.GuardResult guardResult,
            PromptSpec promptSpec
    ) {
        ClassificationResult prediction = guardResult.prediction();
        boolean correct = prediction != ClassificationResult.INVALID && realResult == prediction;
        String rawOutput = "TEMPLATE_GUARD:" + guardResult.matchedTemplatePattern();

        LogEvaluation evaluation = baseEvaluation(dto, template, realResult, promptSpec)
                .aiResult(prediction)
                .decisionSource(BglDecisionSource.TEMPLATE_GUARD)
                .cacheSource(null)
                .matchedTemplatePattern(guardResult.matchedTemplatePattern())
                .rawModelOutput(rawOutput)
                .validModelOutput(true)
                .correct(correct)
                .responseTimeMs(0L)
                .cacheHit(false)
                .cacheable(true)
                .validationStatus("APPROVED")
                .validationReason("Deterministic template guard matched: " + guardResult.matchedTemplatePattern())
                .build();

        logEvaluationRepository.save(evaluation);

        if (templateCacheEnabled) {
            templateCache.putIfCacheable(new BglCachedClassification(
                    template.templateKey(),
                    template.normalizedMessage(),
                    prediction,
                    BglDecisionSource.TEMPLATE_GUARD,
                    guardResult.matchedTemplatePattern(),
                    promptSpec.experiment(),
                    promptSpec.version(),
                    rawOutput,
                    true,
                    true,
                    "APPROVED",
                    "Deterministic template guard matched: " + guardResult.matchedTemplatePattern()
            ));
        }
    }

    private boolean classifyAndSaveWithLlm(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult realResult,
            PromptSpec promptSpec
    ) {
        long start = System.currentTimeMillis();

        ModelClassificationResponse modelResponse =
                callModelAi.classifyWithOllama(
                        template.modelInput(),
                        ollamaModel,
                        promptSpec.prompt(),
                        ollamaApiUrl
                );

        long responseTime = System.currentTimeMillis() - start;

        ClassificationResult prediction = toPrediction(modelResponse);
        boolean correct = prediction != ClassificationResult.INVALID && realResult == prediction;

        String rawModelOutput = modelResponse == null
                ? "NULL_MODEL_RESPONSE"
                : modelResponse.rawOutput();

        boolean validModelOutput = modelResponse != null && modelResponse.valid();

        BglTemplateValidationResult validation = validationService.validateForCache(
                dto.getMessage(),
                template.normalizedMessage(),
                prediction
        );

        boolean cacheable = templateCacheEnabled
                && validModelOutput
                && prediction != ClassificationResult.INVALID
                && (!cacheOnlyValidatedLlmResults || validation.approved());

        LogEvaluation evaluation = baseEvaluation(dto, template, realResult, promptSpec)
                .aiResult(prediction)
                .decisionSource(BglDecisionSource.LLM)
                .cacheSource(null)
                .matchedTemplatePattern(null)
                .rawModelOutput(rawModelOutput)
                .validModelOutput(validModelOutput)
                .correct(correct)
                .responseTimeMs(responseTime)
                .cacheHit(false)
                .cacheable(cacheable)
                .validationStatus(validation.status())
                .validationReason(validation.reason())
                .build();

        logEvaluationRepository.save(evaluation);

        if (cacheable) {
            templateCache.putIfCacheable(new BglCachedClassification(
                    template.templateKey(),
                    template.normalizedMessage(),
                    prediction,
                    BglDecisionSource.LLM,
                    null,
                    promptSpec.experiment(),
                    promptSpec.version(),
                    rawModelOutput,
                    validModelOutput,
                    true,
                    validation.status(),
                    validation.reason()
            ));
        } else {
            log.debug(
                    "LLM result was not cached. prediction={}, valid={}, validationStatus={}, reason={}, template={}",
                    prediction,
                    validModelOutput,
                    validation.status(),
                    validation.reason(),
                    template.normalizedMessage()
            );
        }

        return cacheable;
    }

    private LogEvaluation.LogEvaluationBuilder baseEvaluation(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult realResult,
            PromptSpec promptSpec
    ) {
        return LogEvaluation.builder()
                .log(dto.getMainLog())
                .modelInput(template.modelInput())
                .datasetLabel(dto.getLabel())
                .realResult(realResult)
                .logType(LogType.BGL)
                .aiModel(AiModel.OLLAMA)
                .templateKey(template.templateKey())
                .normalizedTemplate(template.normalizedMessage())
                .promptExperiment(promptSpec.experiment())
                .promptVersion(promptSpec.version())
                .prompt(promptSpec.prompt())
                .createdAt(LocalDateTime.now());
    }

    private record ClassificationFlowStats(
            boolean llmCalled,
            boolean cacheHit,
            boolean cacheHitFromLlm,
            boolean cacheHitFromGuard,
            boolean guardHit,
            boolean notCached
    ) {
        private static ClassificationFlowStats empty() {
            return new ClassificationFlowStats(false, false, false, false, false, false);
        }
    }
}
