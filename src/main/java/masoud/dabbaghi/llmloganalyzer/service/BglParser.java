package masoud.dabbaghi.llmloganalyzer.service;

import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.config.OllamaProperties;
import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRunRepository;
import masoud.dabbaghi.llmloganalyzer.evaluation.BglDecisionSource;
import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import masoud.dabbaghi.llmloganalyzer.evaluation.LogEvaluation;
import masoud.dabbaghi.llmloganalyzer.evaluation.LogEvaluationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
                    + "\\k<location1>\\s+"
                    + "(?<category>[A-Z_]+)\\s+"
                    + "(?<component>[A-Z_]+)\\s+"
                    + "(?<severity>[A-Z_]+)"
                    + "(?:\\s+(?<message>.*))?"
    );

    /*
     * Fallback for valid rows that omit location2 and for a handful of source rows whose
     * location2 text is damaged and contains spaces. RAS/NULL anchors prevent field shifting.
     */
    static final Pattern LOG_PATTERN_WITHOUT_LOCATION2 = Pattern.compile(
            "(?<label>\\S+)\\s+"
                    + "(?<timestamp>\\d+)\\s+"
                    + "(?<date>\\d{4}\\.\\d{2}\\.\\d{2})\\s+"
                    + "(?<location1>\\S+)\\s+"
                    + "(?<datetime>\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+)\\s+"
                    + "(?:(?<location2>.*?)\\s+)?"
                    + "(?<category>RAS|NULL)\\s+"
                    + "(?<component>[A-Z_]+)\\s+"
                    + "(?<severity>[A-Z_]+)"
                    + "(?:\\s+(?<message>.*))?"
    );

    private final CallModelAi callModelAi;
    private final LogEvaluationRepository repository;
    private final BglExperimentRunRepository runRepository;
    private final BglTemplateClassificationCache cache;
    private final BglTemplateValidationService validationService;
    private final OllamaProperties ollamaProperties;
    private final List<LogEvaluation> evaluationBuffer = new ArrayList<>();

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

    @Value("${experiment.git-commit:UNRECORDED}")
    private String gitCommit;

    @Value("${bgl.persistence.batch-size:1000}")
    private int persistenceBatchSize;

    public BglParser(
            CallModelAi callModelAi,
            LogEvaluationRepository repository,
            BglExperimentRunRepository runRepository,
            BglTemplateClassificationCache cache,
            BglTemplateValidationService validationService,
            OllamaProperties ollamaProperties
    ) {
        this.callModelAi = callModelAi;
        this.repository = repository;
        this.runRepository = runRepository;
        this.cache = cache;
        this.validationService = validationService;
        this.ollamaProperties = ollamaProperties;
    }

    static LogBglEntryDto parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        Matcher matcher = LOG_PATTERN.matcher(line);
        boolean hasLocation2 = matcher.matches();

        if (!hasLocation2) {
            matcher = LOG_PATTERN_WITHOUT_LOCATION2.matcher(line);
            if (!matcher.matches()) {
                log.debug("Failed to parse BGL line: {}", line);
                return null;
            }
        }

        return new LogBglEntryDto()
                .setMainLog(line)
                .setLabel(matcher.group("label"))
                .setTimestamp(matcher.group("timestamp"))
                .setDate(matcher.group("date"))
                .setLocation1(matcher.group("location1"))
                .setDatetime(matcher.group("datetime"))
                .setLocation2(hasLocation2 ? matcher.group("location1") : matcher.group("location2"))
                .setCategory(matcher.group("category"))
                .setComponent(matcher.group("component"))
                .setSeverity(matcher.group("severity"))
                .setMessage(Optional.ofNullable(matcher.group("message")).orElse(""));
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

        return switch (response.prediction()) {
            case "normal" -> ClassificationResult.NORMAL;
            case "anomaly" -> ClassificationResult.ANOMALY;
            default -> ClassificationResult.INVALID;
        };
    }

    public synchronized BglExperimentRun logParser() throws IOException {
        AtomicInteger rawLineCount = new AtomicInteger();
        AtomicInteger parsedLineCount = new AtomicInteger();
        AtomicInteger parseErrorCount = new AtomicInteger();
        AtomicInteger llmCallCount = new AtomicInteger();
        AtomicInteger totalCacheHitCount = new AtomicInteger();
        AtomicInteger llmCacheHitCount = new AtomicInteger();
        AtomicInteger guardCacheHitCount = new AtomicInteger();
        AtomicInteger directGuardCount = new AtomicInteger();
        AtomicInteger notCachedCount = new AtomicInteger();
        AtomicInteger invalidModelOutputCount = new AtomicInteger();

        /*
         * Guard enabled:
         *     deterministic Guard + original prompt
         *
         * Guard disabled:
         *     no deterministic Guard + Guard rules embedded in prompt
         */
        PromptSpec prompt = PromptGenerator.finalBglPrompt(guardEnabled);
        callModelAi.validateExperimentConfiguration();

        String classificationMode = guardEnabled
                ? "HYBRID_GUARD_AND_LLM"
                : "PROMPT_ONLY_GUARD_RULES_EMBEDDED";

        String runId = UUID.randomUUID().toString();
        Path datasetPath = Path.of(bglPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(datasetPath) || !Files.isReadable(datasetPath)) {
            throw new IOException("BGL dataset is missing or unreadable: " + datasetPath);
        }
        if (gitCommit == null || gitCommit.isBlank() || "UNRECORDED".equalsIgnoreCase(gitCommit)) {
            throw new IllegalStateException("GIT_COMMIT must identify the exact source revision for an official run");
        }
        String modelVersion = callModelAi.resolveModelVersion();
        long processingStartNanos = System.nanoTime();
        BglExperimentRun run = createExperimentRun(
                runId,
                classificationMode,
                prompt,
                datasetPath,
                modelVersion
        );
        runRepository.save(run);

        /* A run must never inherit cache state from an earlier HTTP request. */
        cache.clear();
        evaluationBuffer.clear();

        log.info(
                """
                        Starting BGL evaluation:
                        runId={}
                        mode={}
                        promptVersion={}
                        model={}
                        templateCacheEnabled={}
                        templateGuardEnabled={}
                        validateBeforeCache={}
                        includeMetadataInTemplateKey={}
                        dataset={}
                        """,
                runId,
                classificationMode,
                prompt.version(),
                ollamaProperties.getModelName(),
                cacheEnabled,
                guardEnabled,
                validateBeforeCache,
                includeMetadata,
                datasetPath
        );

        try {
            long hashStartNanos = System.nanoTime();
            run.setDatasetSha256(sha256(datasetPath));
            run.setDatasetHashDurationMs((System.nanoTime() - hashStartNanos) / 1_000_000L);
            runRepository.save(run);
            processingStartNanos = System.nanoTime();

            try (Stream<String> lines = Files.lines(datasetPath)) {
                lines.forEach(line -> {
                    rawLineCount.incrementAndGet();
                    LogBglEntryDto dto = parseLine(line);
                    if (dto == null) {
                        parseErrorCount.incrementAndGet();
                        return;
                    }

                    FlowStats stats = classify(dto, prompt, runId);

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

                    if (stats.invalidModelOutput()) {
                        invalidModelOutputCount.incrementAndGet();
                    }

                    int processed = parsedLineCount.incrementAndGet();

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
            flushEvaluations();

            finishRun(
                    run,
                    "COMPLETED",
                    null,
                    processingStartNanos,
                    rawLineCount.get(),
                    parsedLineCount.get(),
                    parseErrorCount.get(),
                    llmCallCount.get(),
                    totalCacheHitCount.get(),
                    llmCacheHitCount.get(),
                    guardCacheHitCount.get(),
                    directGuardCount.get(),
                    notCachedCount.get(),
                    invalidModelOutputCount.get()
            );
        } catch (IOException | RuntimeException exception) {
            try {
                flushEvaluations();
            } catch (RuntimeException flushFailure) {
                exception.addSuppressed(flushFailure);
            }
            finishRun(
                    run,
                    "FAILED",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    processingStartNanos,
                    rawLineCount.get(),
                    parsedLineCount.get(),
                    parseErrorCount.get(),
                    llmCallCount.get(),
                    totalCacheHitCount.get(),
                    llmCacheHitCount.get(),
                    guardCacheHitCount.get(),
                    directGuardCount.get(),
                    notCachedCount.get(),
                    invalidModelOutputCount.get()
            );
            throw exception;
        }

        log.info(
                """
                        Finished BGL evaluation:
                        runId={}
                        rawLines={}
                        parsedLines={}
                        parseErrors={}
                        directLlmCalls={}
                        totalCacheHits={}
                        cacheHitsFromLlm={}
                        cacheHitsFromGuard={}
                        directGuardDecisions={}
                        finalCacheSize={}
                        nonCachedLlmResults={}
                        invalidModelOutputs={}
                        """,
                runId,
                rawLineCount.get(),
                parsedLineCount.get(),
                parseErrorCount.get(),
                llmCallCount.get(),
                totalCacheHitCount.get(),
                llmCacheHitCount.get(),
                guardCacheHitCount.get(),
                directGuardCount.get(),
                cache.size(),
                notCachedCount.get(),
                invalidModelOutputCount.get()
        );
        return run;
    }

    private BglExperimentRun createExperimentRun(
            String runId,
            String classificationMode,
            PromptSpec prompt,
            Path datasetPath,
            String modelVersion
    ) {
        Runtime runtime = Runtime.getRuntime();
        return BglExperimentRun.builder()
                .runId(runId)
                .status("RUNNING")
                .startedAt(Instant.now())
                .classificationMode(classificationMode)
                .promptExperiment(prompt.experiment())
                .promptVersion(prompt.version())
                .prompt(prompt.prompt())
                .datasetPath(datasetPath.toString())
                .modelName(ollamaProperties.getModelName())
                .modelVersion(modelVersion)
                .modelDigest(modelVersion)
                .format(ollamaProperties.getFormat())
                .thinkingEnabled(ollamaProperties.isThinking())
                .temperature(ollamaProperties.getOptions().getTemperature())
                .topP(ollamaProperties.getOptions().getTopP())
                .repeatPenalty(ollamaProperties.getOptions().getRepeatPenalty())
                .seed(ollamaProperties.getOptions().getSeed())
                .numCtx(ollamaProperties.getOptions().getNumCtx())
                .numPredict(ollamaProperties.getOptions().getNumPredict())
                .connectTimeoutMs(ollamaProperties.getTimeouts().getConnect().toMillis())
                .responseTimeoutMs(ollamaProperties.getTimeouts().getResponse().toMillis())
                .maxAttempts(ollamaProperties.getRetry().getMaxAttempts())
                .templateCacheEnabled(cacheEnabled)
                .templateGuardEnabled(guardEnabled)
                .validateBeforeCache(validateBeforeCache)
                .includeMetadataInTemplateKey(includeMetadata)
                .gitCommit(gitCommit)
                .javaVersion(System.getProperty("java.version"))
                .osName(System.getProperty("os.name"))
                .osArch(System.getProperty("os.arch"))
                .availableProcessors(runtime.availableProcessors())
                .maxJvmMemoryBytes(runtime.maxMemory())
                .build();
    }

    private void finishRun(
            BglExperimentRun run,
            String status,
            String failureMessage,
            long processingStartNanos,
            int rawLines,
            int parsedLines,
            int parseErrors,
            int llmCalls,
            int totalCacheHits,
            int llmCacheHits,
            int guardCacheHits,
            int directGuardDecisions,
            int nonCachedResults,
            int invalidModelOutputs
    ) {
        long processingDurationMs = (System.nanoTime() - processingStartNanos) / 1_000_000L;
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        run.setFailureMessage(failureMessage);
        run.setRawLineCount(rawLines);
        run.setParsedLineCount(parsedLines);
        run.setParseErrorCount(parseErrors);
        run.setDirectLlmCalls(llmCalls);
        run.setTotalCacheHits(totalCacheHits);
        run.setCacheHitsFromLlm(llmCacheHits);
        run.setCacheHitsFromGuard(guardCacheHits);
        run.setDirectGuardDecisions(directGuardDecisions);
        run.setNonCachedLlmResults(nonCachedResults);
        run.setInvalidModelOutputs(invalidModelOutputs);
        run.setFinalCacheSize(cache.size());
        run.setProcessingDurationMs(processingDurationMs);
        run.setThroughputLinesPerSecond(
                processingDurationMs == 0 ? 0 : parsedLines * 1000.0 / processingDurationMs
        );
        runRepository.save(run);
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void saveEvaluation(LogEvaluation evaluation) {
        evaluationBuffer.add(evaluation);
        if (evaluationBuffer.size() >= Math.max(1, persistenceBatchSize)) {
            flushEvaluations();
        }
    }

    private void flushEvaluations() {
        if (evaluationBuffer.isEmpty()) {
            return;
        }
        repository.saveAll(evaluationBuffer);
        evaluationBuffer.clear();
    }

    private FlowStats classify(
            LogBglEntryDto dto,
            PromptSpec prompt,
            String runId
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
                        prompt,
                        runId
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
                        prompt,
                        runId
                );

                return FlowStats.directGuard();
            }
        }

        /*
         * Stage 3: LLM
         *
         * When Guard is disabled, the selected prompt contains Guard knowledge.
         */
        LlmOutcome outcome = processLlmResult(
                dto,
                template,
                cacheKey,
                truth,
                prompt,
                runId
        );

        return FlowStats.directLlm(outcome.cached(), outcome.validOutput());
    }

    private FlowStats processCachedResult(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult truth,
            BglCachedClassification cached,
            PromptSpec prompt,
            String runId
    ) {
        ClassificationResult prediction = cached.getPrediction();
        BglDecisionSource originalSource = cached.getOriginalDecisionSource();

        saveEvaluation(
                createBaseEvaluation(dto, template, truth, prompt, runId)
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
            PromptSpec prompt,
            String runId
    ) {
        ClassificationResult prediction = guardResult.prediction();
        String matchedRule = guardResult.matchedTemplatePattern();

        String rawOutput =
                "TEMPLATE_GUARD:" + matchedRule;

        String validationReason =
                "Deterministic template guard matched: " + matchedRule;

        saveEvaluation(
                createBaseEvaluation(dto, template, truth, prompt, runId)
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

    private LlmOutcome processLlmResult(
            LogBglEntryDto dto,
            BglTemplate template,
            String cacheKey,
            ClassificationResult truth,
            PromptSpec prompt,
            String runId
    ) {
        long startTime = System.currentTimeMillis();

        ModelClassificationResponse response =
                callModelAi.classifyWithOllama(template.modelInput(), prompt.prompt());

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

        saveEvaluation(
                createBaseEvaluation(dto, template, truth, prompt, runId)
                        .aiResult(prediction)
                        .decisionSource(BglDecisionSource.LLM)
                        .cacheSource(null)
                        .matchedTemplatePattern(null)
                        .rawModelOutput(rawOutput)
                        .validModelOutput(validOutput)
                        .modelPrediction(response == null ? null : response.prediction())
                        .modelConfidence(response == null || !response.valid() ? null : response.confidence())
                        .modelReason(response == null ? null : response.reason())
                        .modelCategory(response == null ? null : response.category())
                        .modelValidationError(response == null ? "NULL_MODEL_RESPONSE" : response.validationError())
                        .promptTokenCount(response == null ? null : response.promptTokenCount())
                        .outputTokenCount(response == null ? null : response.outputTokenCount())
                        .modelTotalDurationNanos(response == null ? null : response.totalDurationNanos())
                        .modelLoadDurationNanos(response == null ? null : response.loadDurationNanos())
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

        return new LlmOutcome(cacheable, validOutput);
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
        String configuredModel = ollamaProperties.getModelName();
        String modelName =
                configuredModel == null || configuredModel.isBlank()
                        ? "UNKNOWN_MODEL"
                        : configuredModel.trim();

        return "prompt=" + prompt.version()
                + "|model=" + modelName
                + "|" + template.templateKey();
    }

    private LogEvaluation.LogEvaluationBuilder createBaseEvaluation(
            LogBglEntryDto dto,
            BglTemplate template,
            ClassificationResult truth,
            PromptSpec prompt,
            String runId
    ) {
        return LogEvaluation.builder()
                .runId(runId)
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
                .createdAt(Instant.now());
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
            boolean notCached,
            boolean invalidModelOutput
    ) {

        private static final FlowStats EMPTY =
                new FlowStats(
                        false,
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
                    false,
                    false
            );
        }

        private static FlowStats directLlm(boolean cached, boolean validOutput) {
            return new FlowStats(
                    true,
                    false,
                    false,
                    false,
                    false,
                    !cached,
                    !validOutput
            );
        }
    }

    private record LlmOutcome(boolean cached, boolean validOutput) {
    }
}
