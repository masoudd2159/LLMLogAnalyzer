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
            "(?<label>\\S+)\\s+"
                    + "(?<timestamp>\\d+)\\s+"
                    + "(?<date>\\d{4}\\.\\d{2}\\.\\d{2})\\s+"
                    + "(?<location1>\\S+)\\s+"
                    + "(?<datetime>\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+)\\s+"
                    + "(?<location2>\\S+)\\s+"
                    + "(?<category>[A-Z]+)\\s+"
                    + "(?<component>[A-Z]+)\\s+"
                    + "(?<severity>[A-Z]+)\\s+"
                    + "(?<message>.*)"
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
        if (line == null || line.isBlank()) {
            return null;
        }

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

    private static ClassificationResult groundTruth(String label) {
        return "-".equals(label)
                ? ClassificationResult.NORMAL
                : ClassificationResult.ANOMALY;
    }

    private static ClassificationResult prediction(
            ModelClassificationResponse response
    ) {
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
        AtomicInteger totalCount = new AtomicInteger();
        AtomicInteger llmCallCount = new AtomicInteger();
        AtomicInteger totalCacheHitCount = new AtomicInteger();
        AtomicInteger llmCacheHitCount = new AtomicInteger();
        AtomicInteger guardCacheHitCount = new AtomicInteger();
        AtomicInteger directGuardCount = new AtomicInteger();
        AtomicInteger notCachedCount = new AtomicInteger();

        /*
         * Guard enabled:
         *     deterministic Guard + original prompt
         *
         * Guard disabled:
         *     no deterministic Guard + Guard rules embedded in prompt
         */
        PromptSpec prompt = PromptGenerator.finalBglPrompt(guardEnabled);

        String classificationMode = guardEnabled
                ? "HYBRID_GUARD_AND_LLM"
                : "PROMPT_ONLY_GUARD_RULES_EMBEDDED";

        log.info(
                """
                        Starting BGL evaluation:
                        mode={}
                        promptVersion={}
                        model={}
                        templateCacheEnabled={}
                        templateGuardEnabled={}
                        validateBeforeCache={}
                        includeMetadataInTemplateKey={}
                        dataset={}
                        """,
                classificationMode,
                prompt.version(),
                ollamaModel,
                cacheEnabled,
                guardEnabled,
                validateBeforeCache,
                includeMetadata,
                bglPath
        );

        try (Stream<String> lines = Files.lines(Path.of(bglPath))) {
            lines.map(BglParser::parseLine)
                    .filter(Objects::nonNull)
                    .forEach(dto -> {
                        FlowStats stats = classify(dto, prompt);

                        if (stats.llmCalled()) {
                            llmCallCount.incrementAndGet();
                        }

                        if (stats.cacheHit()) {
                            totalCacheHitCount.incrementAndGet();
                        }

                        if (stats.cacheFromLlm()) {
                            llmCacheHitCount.incrementAndGet();
                        }

                        if (stats.cacheFromGuard()) {
                            guardCacheHitCount.incrementAndGet();
                        }

                        if (stats.guardHit()) {
                            directGuardCount.incrementAndGet();
                        }

                        if (stats.notCached()) {
                            notCachedCount.incrementAndGet();
                        }

                        int processed = totalCount.incrementAndGet();

                        if (processed % 1000 == 0) {
                            logProgress(
                                    processed,
                                    llmCallCount.get(),
                                    totalCacheHitCount.get(),
                                    llmCacheHitCount.get(),
                                    guardCacheHitCount.get(),
                                    directGuardCount.get(),
                                    notCachedCount.get()
                            );
                        }
                    });
        }

        log.info(
                """
                        Finished BGL evaluation:
                        total={}
                        directLlmCalls={}
                        totalCacheHits={}
                        cacheHitsFromLlm={}
                        cacheHitsFromGuard={}
                        directGuardDecisions={}
                        finalCacheSize={}
                        nonCachedLlmResults={}
                        """,
                totalCount.get(),
                llmCallCount.get(),
                totalCacheHitCount.get(),
                llmCacheHitCount.get(),
                guardCacheHitCount.get(),
                directGuardCount.get(),
                cache.size(),
                notCachedCount.get()
        );
    }

    private FlowStats classify(
            LogBglEntryDto dto,
            PromptSpec prompt
    ) {
        if (dto == null || dto.getMessage() == null) {
            return FlowStats.EMPTY;
        }

        ClassificationResult truth = groundTruth(dto.getLabel());

        BglTemplate template = BglTemplateExtractor.extract(
                dto,
                includeMetadata
        );

        String cacheKey = createCacheKey(template, prompt);

        /*
         * Stage 1: Template cache
         */
        if (cacheEnabled) {
            Optional<BglCachedClassification> cachedResult =
                    cache.find(cacheKey);

            if (cachedResult.isPresent()) {
                return processCachedResult(
                        dto,
                        template,
                        truth,
                        cachedResult.get(),
                        prompt
                );
            }
        }

        /*
         * Stage 2: Deterministic Guard
         *
         * This block is skipped completely when:
         *
         * bgl.classification.template-guard.enabled=false
         */
        if (guardEnabled) {
            Optional<BglTemplateGuard.GuardResult> guardResult =
                    BglTemplateGuard.classify(
                            dto.getMessage(),
                            template.normalizedMessage()
                    );

            if (guardResult.isPresent()) {
                processGuardResult(
                        dto,
                        template,
                        cacheKey,
                        truth,
                        guardResult.get(),
                        prompt
                );

                return FlowStats.directGuard();
            }
        }

        /*
         * Stage 3: LLM
         *
         * When Guard is disabled, the selected prompt contains Guard knowledge.
         */
        boolean resultCached = processLlmResult(
                dto,
                template,
                cacheKey,
                truth,
                prompt
        );

        return FlowStats.directLlm(resultCached);
    }

    private FlowStats processCachedResult(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult truth,
            BglCachedClassification cached,
            PromptSpec prompt
    ) {
        ClassificationResult prediction = cached.getPrediction();
        BglDecisionSource originalSource = cached.getOriginalDecisionSource();

        repository.save(
                createBaseEvaluation(dto, template, truth, prompt)
                        .aiResult(prediction)
                        .decisionSource(BglDecisionSource.TEMPLATE_CACHE)
                        .cacheSource(originalSource)
                        .matchedTemplatePattern(
                                cached.getMatchedTemplatePattern()
                        )
                        .rawModelOutput(
                                "TEMPLATE_CACHE:"
                                        + originalSource
                                        + ":"
                                        + cached.getRawModelOutput()
                        )
                        .validModelOutput(cached.isValidModelOutput())
                        .correct(truth == prediction)
                        .responseTimeMs(0L)
                        .cacheHit(true)
                        .cacheable(true)
                        .validationStatus(cached.getValidationStatus())
                        .validationReason(cached.getValidationReason())
                        .build()
        );

        return new FlowStats(
                false,
                true,
                originalSource == BglDecisionSource.LLM,
                originalSource == BglDecisionSource.TEMPLATE_GUARD,
                false,
                false
        );
    }

    private void processGuardResult(
            LogBglEntryDto dto,
            BglTemplate template,
            String cacheKey,
            ClassificationResult truth,
            BglTemplateGuard.GuardResult guardResult,
            PromptSpec prompt
    ) {
        ClassificationResult prediction = guardResult.prediction();
        String matchedRule = guardResult.matchedTemplatePattern();

        String rawOutput =
                "TEMPLATE_GUARD:" + matchedRule;

        String validationReason =
                "Deterministic template guard matched: " + matchedRule;

        repository.save(
                createBaseEvaluation(dto, template, truth, prompt)
                        .aiResult(prediction)
                        .decisionSource(BglDecisionSource.TEMPLATE_GUARD)
                        .cacheSource(null)
                        .matchedTemplatePattern(matchedRule)
                        .rawModelOutput(rawOutput)
                        .validModelOutput(true)
                        .correct(truth == prediction)
                        .responseTimeMs(0L)
                        .cacheHit(false)
                        .cacheable(cacheEnabled)
                        .validationStatus("APPROVED")
                        .validationReason(validationReason)
                        .build()
        );

        if (!cacheEnabled) {
            return;
        }

        cache.putIfCacheable(
                new BglCachedClassification(
                        cacheKey,
                        template.normalizedMessage(),
                        prediction,
                        BglDecisionSource.TEMPLATE_GUARD,
                        matchedRule,
                        prompt.experiment(),
                        prompt.version(),
                        rawOutput,
                        true,
                        true,
                        "APPROVED",
                        validationReason
                )
        );
    }

    private boolean processLlmResult(
            LogBglEntryDto dto,
            BglTemplate template,
            String cacheKey,
            ClassificationResult truth,
            PromptSpec prompt
    ) {
        long startTime = System.currentTimeMillis();

        ModelClassificationResponse response =
                callModelAi.classifyWithOllama(
                        template.modelInput(),
                        ollamaModel,
                        prompt.prompt(),
                        ollamaApiUrl
                );

        long responseTime =
                System.currentTimeMillis() - startTime;

        ClassificationResult prediction = prediction(response);

        boolean validOutput =
                response != null && response.valid();

        String rawOutput = response == null
                ? "NULL_MODEL_RESPONSE"
                : response.rawOutput();

        /*
         * guardEnabled=true:
         *     Validation may compare the LLM result with deterministic Guard rules.
         *
         * guardEnabled=false:
         *     Validation must not call BglTemplateGuard.
         */
        BglTemplateValidationResult validation =
                validationService.validateForCache(
                        dto.getMessage(),
                        template.normalizedMessage(),
                        prediction,
                        guardEnabled
                );

        boolean cacheable = isCacheable(
                prediction,
                validOutput,
                validation
        );

        repository.save(
                createBaseEvaluation(dto, template, truth, prompt)
                        .aiResult(prediction)
                        .decisionSource(BglDecisionSource.LLM)
                        .cacheSource(null)
                        .matchedTemplatePattern(null)
                        .rawModelOutput(rawOutput)
                        .validModelOutput(validOutput)
                        .correct(
                                prediction != ClassificationResult.INVALID
                                        && truth == prediction
                        )
                        .responseTimeMs(responseTime)
                        .cacheHit(false)
                        .cacheable(cacheable)
                        .validationStatus(validation.status())
                        .validationReason(validation.reason())
                        .build()
        );

        if (cacheable) {
            cache.putIfCacheable(
                    new BglCachedClassification(
                            cacheKey,
                            template.normalizedMessage(),
                            prediction,
                            BglDecisionSource.LLM,
                            null,
                            prompt.experiment(),
                            prompt.version(),
                            rawOutput,
                            validOutput,
                            true,
                            validation.status(),
                            validation.reason()
                    )
            );
        }

        return cacheable;
    }

    private boolean isCacheable(
            ClassificationResult prediction,
            boolean validOutput,
            BglTemplateValidationResult validation
    ) {
        if (!cacheEnabled) {
            return false;
        }

        if (!validOutput) {
            return false;
        }

        if (prediction == null
                || prediction == ClassificationResult.INVALID) {
            return false;
        }

        return !validateBeforeCache || validation.approved();
    }

    private String createCacheKey(
            BglTemplate template,
            PromptSpec prompt
    ) {
        String modelName =
                ollamaModel == null || ollamaModel.isBlank()
                        ? "UNKNOWN_MODEL"
                        : ollamaModel.trim();

        return "prompt=" + prompt.version()
                + "|model=" + modelName
                + "|" + template.templateKey();
    }

    private LogEvaluation.LogEvaluationBuilder createBaseEvaluation(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult truth,
            PromptSpec prompt
    ) {
        return LogEvaluation.builder()
                .log(dto.getMainLog())
                .modelInput(template.modelInput())
                .datasetLabel(dto.getLabel())
                .realResult(truth)
                .logType(LogType.BGL)
                .aiModel(AiModel.OLLAMA)
                .templateKey(template.templateKey())
                .normalizedTemplate(template.normalizedMessage())
                .promptExperiment(prompt.experiment())
                .promptVersion(prompt.version())
                .prompt(prompt.prompt())
                .createdAt(LocalDateTime.now());
    }

    private void logProgress(
            int processed,
            int llmCalls,
            int totalCacheHits,
            int llmCacheHits,
            int guardCacheHits,
            int directGuardDecisions,
            int notCached
    ) {
        log.info(
                "Progress: processed={}, directLlmCalls={}, totalCacheHits={}, llmCacheHits={}, guardCacheHits={}, directGuardDecisions={}, cacheSize={}, notCached={}",
                processed,
                llmCalls,
                totalCacheHits,
                llmCacheHits,
                guardCacheHits,
                directGuardDecisions,
                cache.size(),
                notCached
        );
    }

    private record FlowStats(
            boolean llmCalled,
            boolean cacheHit,
            boolean cacheFromLlm,
            boolean cacheFromGuard,
            boolean guardHit,
            boolean notCached
    ) {

        private static final FlowStats EMPTY =
                new FlowStats(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false
                );

        private static FlowStats directGuard() {
            return new FlowStats(
                    false,
                    false,
                    false,
                    false,
                    true,
                    false
            );
        }

        private static FlowStats directLlm(boolean cached) {
            return new FlowStats(
                    true,
                    false,
                    false,
                    false,
                    false,
                    !cached
            );
        }
    }
}