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

    @Value("${model.api.ollama.url}")
    private String ollamaApiUrl;

    @Value("${model.api.ollama.model-name}")
    private String ollamaModel;

    @Value("${bgl.location}")
    private String bglPath;

    public BglParser(
            CallModelAi callModelAi,
            LogEvaluationRepository logEvaluationRepository
    ) {
        this.callModelAi = callModelAi;
        this.logEvaluationRepository = logEvaluationRepository;
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

    /*
     * Critical thesis point:
     * The original BGL dataset label is intentionally removed from the model input.
     * Ground truth is used only after inference for evaluation.
     */
    private static String buildModelInputWithoutDatasetLabel(LogBglEntryDto dto) {
        return """
                timestamp=%s
                date=%s
                location1=%s
                datetime=%s
                location2=%s
                category=%s
                component=%s
                severity=%s
                message=%s
                """.formatted(
                safe(dto.getTimestamp()),
                safe(dto.getDate()),
                safe(dto.getLocation1()),
                safe(dto.getDatetime()),
                safe(dto.getLocation2()),
                safe(dto.getCategory()),
                safe(dto.getComponent()),
                safe(dto.getSeverity()),
                safe(dto.getMessage())
        );
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public void logParser() throws IOException {
        AtomicInteger processedCount = new AtomicInteger(0);
        PromptSpec finalPrompt = PromptGenerator.finalBglPrompt();

        log.info(
                "Starting BGL parsing with final prompt: experiment={}, version={}",
                finalPrompt.experiment(),
                finalPrompt.version()
        );

        try (Stream<String> lines = Files.lines(Path.of(bglPath))) {
            lines.map(BglParser::parseLine)
                    .filter(Objects::nonNull)
                    .forEach(dto -> {
                        classifyAndSaveWithFinalMethod(dto, finalPrompt);

                        int count = processedCount.incrementAndGet();
                        if (count % 100 == 0) {
                            log.info("Processed BGL lines: {}", count);
                        }
                    });
        }

        log.info("Finished BGL parsing. Total processed lines: {}", processedCount.get());
    }

    private void classifyAndSaveWithFinalMethod(
            LogBglEntryDto dto,
            PromptSpec promptSpec
    ) {
        if (dto == null || dto.getMessage() == null) {
            log.warn("Skipping invalid BGL log entry: {}", dto);
            return;
        }

        String modelInput = buildModelInputWithoutDatasetLabel(dto);
        ClassificationResult realResult = toGroundTruth(dto.getLabel());

        /*
         * Step 1:
         * Try deterministic template guard first.
         * This reduces false positives for known-normal BGL templates.
         */
        Optional<BglTemplateGuard.GuardResult> guardResult =
                BglTemplateGuard.classify(dto.getMessage());

        if (guardResult.isPresent()) {
            saveGuardedEvaluation(
                    dto,
                    modelInput,
                    realResult,
                    guardResult.get(),
                    promptSpec
            );
            return;
        }

        /*
         * Step 2:
         * If no deterministic template matched, use the final template-aware prompt.
         */
        classifyAndSaveWithLlm(dto, modelInput, realResult, promptSpec);
    }

    private void saveGuardedEvaluation(
            LogBglEntryDto dto,
            String modelInput,
            ClassificationResult realResult,
            BglTemplateGuard.GuardResult guardResult,
            PromptSpec promptSpec
    ) {
        ClassificationResult prediction = guardResult.prediction();

        boolean correct =
                prediction != ClassificationResult.INVALID
                        && realResult == prediction;

        LogEvaluation evaluation =
                LogEvaluation.builder()
                        .log(dto.getMainLog())
                        .modelInput(modelInput)
                        .datasetLabel(dto.getLabel())
                        .realResult(realResult)
                        .aiResult(prediction)
                        .logType(LogType.BGL)
                        .aiModel(AiModel.OLLAMA)
                        .decisionSource(BglDecisionSource.TEMPLATE_GUARD)
                        .matchedTemplatePattern(guardResult.matchedTemplatePattern())
                        .promptExperiment(promptSpec.experiment())
                        .promptVersion(promptSpec.version())
                        .prompt(promptSpec.prompt())
                        .rawModelOutput("TEMPLATE_GUARD:" + guardResult.matchedTemplatePattern())
                        .validModelOutput(true)
                        .correct(correct)
                        .responseTimeMs(0L)
                        .createdAt(LocalDateTime.now())
                        .build();

        logEvaluationRepository.save(evaluation);
    }

    private void classifyAndSaveWithLlm(
            LogBglEntryDto dto,
            String modelInput,
            ClassificationResult realResult,
            PromptSpec promptSpec
    ) {
        long start = System.currentTimeMillis();

        ModelClassificationResponse modelResponse =
                callModelAi.classifyWithOllama(
                        modelInput,
                        ollamaModel,
                        promptSpec.prompt(),
                        ollamaApiUrl
                );

        long responseTime = System.currentTimeMillis() - start;

        ClassificationResult prediction = toPrediction(modelResponse);

        boolean correct =
                prediction != ClassificationResult.INVALID
                        && realResult == prediction;

        String rawModelOutput = modelResponse == null
                ? "NULL_MODEL_RESPONSE"
                : modelResponse.rawOutput();

        boolean validModelOutput = modelResponse != null && modelResponse.valid();

        LogEvaluation evaluation =
                LogEvaluation.builder()
                        .log(dto.getMainLog())
                        .modelInput(modelInput)
                        .datasetLabel(dto.getLabel())
                        .realResult(realResult)
                        .aiResult(prediction)
                        .logType(LogType.BGL)
                        .aiModel(AiModel.OLLAMA)
                        .decisionSource(BglDecisionSource.LLM)
                        .matchedTemplatePattern(null)
                        .promptExperiment(promptSpec.experiment())
                        .promptVersion(promptSpec.version())
                        .prompt(promptSpec.prompt())
                        .rawModelOutput(rawModelOutput)
                        .validModelOutput(validModelOutput)
                        .correct(correct)
                        .responseTimeMs(responseTime)
                        .createdAt(LocalDateTime.now())
                        .build();

        logEvaluationRepository.save(evaluation);
    }
}
