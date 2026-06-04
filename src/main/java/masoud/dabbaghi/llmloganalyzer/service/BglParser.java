package masoud.dabbaghi.llmloganalyzer.service;

import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.evaluation.ClassificationResult;
import masoud.dabbaghi.llmloganalyzer.evaluation.LogEvaluation;
import masoud.dabbaghi.llmloganalyzer.evaluation.LogEvaluationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final LogEvaluationRepository logEvaluationService;
    @Value("${model.api.gpt.url}")
    private String gptApiURL;
    @Value("${model.api.ollama.url}")
    private String ollamaApiUrl;
    @Value("${model.api.gpt.model-name}")
    private String gptModel;
    @Value("${model.api.ollama.model-name}")
    private String ollamaModel;
    @Value("${bgl.location}")
    private String bglPath;

    public BglParser(CallModelAi callModelAi, LogEvaluationRepository logEvaluationService) {
        this.callModelAi = callModelAi;
        this.logEvaluationService = logEvaluationService;
    }

    private static LogBglEntryDto parseLine(String line) {
        Matcher matcher = LOG_PATTERN.matcher(line);
        if (matcher.matches()) {
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
        } else {
            log.error("Failed to parse line: {}", line);
            return null;
        }
    }

    public void logParser() throws IOException {
        List<String> lines = Files.readAllLines(Path.of(bglPath));
        int size = lines.size();

        for (int i = 0; i < size; i += 10) {
            int end = Math.min(i + 10, size);
            List<LogBglEntryDto> dtos = lines.subList(i, end)
                    .stream()
                    .map(BglParser::parseLine)
                    .filter(Objects::nonNull)
                    .toList();

            makeAndSaveBglPrompt(dtos);

            int remaining = size - end;
            log.info("Remaining lines: {}", remaining);
        }
    }

    private void makeAndSaveBglPrompt(List<LogBglEntryDto> dtos) {

        String prompt = PromptGenerator.BGL_PROMPT;

        dtos.forEach(dto -> {

            if (dto == null || dto.getMainLog() == null) {
                log.warn("Skipping invalid log entry: {}", dto);
                return;
            }

            long start = System.currentTimeMillis();

            String aiResult =
                    callModelAi.getOllamaResult(
                            dto,
                            ollamaModel,
                            prompt,
                            ollamaApiUrl
                    );

            long responseTime =
                    System.currentTimeMillis() - start;

            ClassificationResult realResult =
                    "-".equals(dto.getLabel())
                            ? ClassificationResult.NORMAL
                            : ClassificationResult.ANOMALY;

            ClassificationResult prediction =
                    "1".equals(aiResult)
                            ? ClassificationResult.ANOMALY
                            : ClassificationResult.NORMAL;

            LogEvaluation evaluation =
                    LogEvaluation.builder()
                            .log(dto.getMainLog())
                            .realResult(realResult)
                            .logType(LogType.BGL)
                            .prompt(prompt)
                            .aiResult(prediction)
                            .aiModel(AiModel.OLLAMA)
                            .correct(realResult == prediction)
                            .responseTimeMs(responseTime)
                            .createdAt(LocalDateTime.now())
                            .build();

            logEvaluationService.save(evaluation);
        });
    }
}
