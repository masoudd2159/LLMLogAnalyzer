package masoud.dabbaghi.llmloganalyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import masoud.dabbaghi.llmloganalyzer.config.OllamaProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Ollama client with schema-constrained, deterministic Qwen3.5 inference. */
@Service
@Slf4j
public class CallModelAi {

    private static final Set<String> REQUIRED_FIELDS =
            Set.of("prediction", "confidence", "reason", "category");
    private static final Set<String> CATEGORIES =
            Set.of("hardware", "software", "network", "storage", "job", "diagnostic", "environment", "unknown");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final OllamaProperties properties;

    public CallModelAi(WebClient ollamaWebClient, ObjectMapper objectMapper, OllamaProperties properties) {
        this.webClient = ollamaWebClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ModelClassificationResponse classifyWithOllama(String modelInput, String prompt) {
        int estimatedInputTokens = estimateTokens(prompt) + estimateTokens(modelInput) + 64;
        int availableInputTokens = properties.getOptions().getNumCtx() - properties.getOptions().getNumPredict();
        if (estimatedInputTokens > availableInputTokens) {
            return invalid(
                    "CONTEXT_BUDGET_EXCEEDED",
                    "Estimated input is " + estimatedInputTokens + " tokens but budget is " + availableInputTokens
            );
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getModelName());
        request.put("stream", false);
        request.put("think", properties.isThinking());
        request.put("keep_alive", properties.getKeepAlive());
        request.put("messages", List.of(
                Map.of("role", "system", "content", prompt),
                Map.of("role", "user", "content", buildUserMessage(modelInput))
        ));
        request.put("format", requestFormat());
        request.put("options", ollamaOptions());

        try {
            JsonNode response = webClient.post()
                    .uri(properties.getChatPath())
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(properties.getTimeouts().getResponse())
                    .retryWhen(retrySpec("chat completion"))
                    .block();

            if (response == null) {
                return invalid("NULL_RESPONSE", "Ollama returned no response body");
            }
            if (!response.path("done").asBoolean(false)) {
                return invalid(response.toString(), "Ollama response was incomplete");
            }

            String content = response.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                return invalid(response.toString(), "Ollama response contained no message content");
            }
            return parseClassification(content).withUsage(
                    response.path("prompt_eval_count").asInt(0),
                    response.path("eval_count").asInt(0),
                    response.path("total_duration").asLong(0),
                    response.path("load_duration").asLong(0)
            );
        } catch (Exception exception) {
            log.error("Ollama request failed after {} attempt(s)", properties.getRetry().getMaxAttempts(), exception);
            return invalid("OLLAMA_API_ERROR: " + exception.getMessage(), "Ollama API request failed");
        }
    }

    /** Resolve the immutable local manifest digest so each experiment records an exact model build. */
    public String resolveModelVersion() {
        String configured = properties.getModelVersion();
        if (configured != null && !configured.isBlank() && !"AUTO".equalsIgnoreCase(configured)) {
            return configured.trim();
        }

        JsonNode response = webClient.get()
                .uri("/api/tags")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(properties.getTimeouts().getConnect().plusSeconds(10))
                .retryWhen(retrySpec("model metadata lookup"))
                .block();

        if (response != null) {
            for (JsonNode model : response.path("models")) {
                String name = model.path("name").asText();
                String alias = model.path("model").asText();
                if (properties.getModelName().equals(name) || properties.getModelName().equals(alias)) {
                    String digest = model.path("digest").asText();
                    if (!digest.isBlank()) {
                        return digest;
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Required Ollama model '" + properties.getModelName() + "' is unavailable or has no digest"
        );
    }

    /** Fail fast if a thesis run is not using the frozen official experiment settings. */
    public void validateExperimentConfiguration() {
        properties.validateOfficialExperiment();
    }

    private Object requestFormat() {
        return "json".equalsIgnoreCase(properties.getFormat())
                ? jsonFormatSchema()
                : properties.getFormat();
    }

    private String buildUserMessage(String modelInput) {
        return """
                Classify this single label-free BGL log template. Use only evidence in this record.
                Return exactly one JSON object matching the required schema and no other text.

                BGL_RECORD:
                %s
                """.formatted(modelInput);
    }

    private Map<String, Object> jsonFormatSchema() {
        Map<String, Object> propertiesSchema = new LinkedHashMap<>();
        propertiesSchema.put("prediction", Map.of("type", "string", "enum", List.of("normal", "anomaly")));
        propertiesSchema.put("confidence", Map.of("type", "number", "minimum", 0.0, "maximum", 1.0));
        propertiesSchema.put("reason", Map.of("type", "string", "minLength", 1, "maxLength", 240));
        propertiesSchema.put("category", Map.of(
                "type", "string",
                "enum", List.of("hardware", "software", "network", "storage", "job", "diagnostic", "environment", "unknown")
        ));
        return Map.of(
                "type", "object",
                "properties", propertiesSchema,
                "required", List.of("prediction", "confidence", "reason", "category"),
                "additionalProperties", false
        );
    }

    private Map<String, Object> ollamaOptions() {
        OllamaProperties.Options options = properties.getOptions();
        return Map.of(
                "temperature", options.getTemperature(),
                "top_p", options.getTopP(),
                "repeat_penalty", options.getRepeatPenalty(),
                "seed", options.getSeed(),
                "num_ctx", options.getNumCtx(),
                "num_predict", options.getNumPredict()
        );
    }

    ModelClassificationResponse parseClassification(String content) {
        String jsonText = extractJsonObject(content);
        try {
            JsonNode json = objectMapper.readTree(jsonText);
            if (!json.isObject() || !hasExactlyRequiredFields(json)) {
                return invalid(content, "Output must contain exactly prediction, confidence, reason, and category");
            }

            String prediction = json.path("prediction").asText();
            JsonNode confidenceNode = json.path("confidence");
            String reason = json.path("reason").asText();
            String category = json.path("category").asText();
            double confidence = confidenceNode.asDouble(Double.NaN);

            if (!("normal".equals(prediction) || "anomaly".equals(prediction))) {
                return invalid(content, "prediction must be normal or anomaly");
            }
            if (!confidenceNode.isNumber() || !Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                return invalid(content, "confidence must be a number from 0.0 through 1.0");
            }
            if (reason.isBlank() || reason.length() > 240) {
                return invalid(content, "reason must contain 1 to 240 characters");
            }
            if (!CATEGORIES.contains(category)) {
                return invalid(content, "category is not supported");
            }
            return ModelClassificationResponse.valid(prediction, confidence, reason, category, content);
        } catch (Exception exception) {
            return invalid(content, "Output is not complete valid JSON: " + exception.getMessage());
        }
    }

    private boolean hasExactlyRequiredFields(JsonNode json) {
        Iterator<String> names = json.fieldNames();
        java.util.HashSet<String> actual = new java.util.HashSet<>();
        names.forEachRemaining(actual::add);
        return actual.equals(REQUIRED_FIELDS);
    }

    /** Accept whitespace and one enclosing Markdown fence; reject prose and truncated JSON. */
    private String extractJsonObject(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }

        int start = text.indexOf('{');
        if (start != 0) {
            return text;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\' && inString) {
                escaped = true;
            } else if (character == '"') {
                inString = !inString;
            } else if (!inString && character == '{') {
                depth++;
            } else if (!inString && character == '}' && --depth == 0) {
                return text.substring(index + 1).isBlank()
                        ? text.substring(start, index + 1)
                        : text;
            }
        }
        return text.substring(start);
    }

    private Retry retrySpec(String operation) {
        OllamaProperties.Retry retry = properties.getRetry();
        long retryCount = Math.max(0, retry.getMaxAttempts() - 1L);
        return Retry.backoff(retryCount, nonZero(retry.getInitialBackoff()))
                .maxBackoff(nonZero(retry.getMaxBackoff()))
                .filter(this::isRetryable)
                .doBeforeRetry(signal -> log.warn(
                        "Retrying Ollama {} ({}/{}): {}",
                        operation,
                        signal.totalRetries() + 2,
                        retry.getMaxAttempts(),
                        signal.failure().toString()
                ));
    }

    private Duration nonZero(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative()
                ? Duration.ofMillis(1)
                : duration;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // Conservative language-independent estimate; actual counts are persisted from Ollama.
        return (text.length() + 2) / 3;
    }

    private boolean isRetryable(Throwable error) {
        if (error instanceof TimeoutException || error instanceof WebClientRequestException) {
            return true;
        }
        return error instanceof WebClientResponseException response
                && (response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError());
    }

    private ModelClassificationResponse invalid(String rawOutput, String reason) {
        log.warn("Invalid Ollama output: {}. Raw output: {}", reason, rawOutput);
        return ModelClassificationResponse.invalid(rawOutput, reason);
    }
}
