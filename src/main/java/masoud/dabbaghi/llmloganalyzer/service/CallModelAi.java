package masoud.dabbaghi.llmloganalyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class CallModelAi {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${model.api.ollama.options.temperature:0}")
    private double temperature;

    @Value("${model.api.ollama.options.top-p:0.1}")
    private double topP;

    @Value("${model.api.ollama.options.repeat-penalty:1.0}")
    private double repeatPenalty;

    @Value("${model.api.ollama.options.seed:42}")
    private int seed;

    @Value("${model.api.ollama.options.num-ctx:2048}")
    private int numCtx;

    @Value("${model.api.ollama.options.num-predict:8}")
    private int numPredict;

    public CallModelAi(WebClient webClient) {
        this.webClient = webClient;
    }

    public ModelClassificationResponse classifyWithOllama(
            String modelInput,
            String model,
            String prompt,
            String apiURL
    ) {
        Map<String, Object> request = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", prompt),
                        Map.of("role", "user", "content", buildUserMessage(modelInput))
                ),
                "format", jsonFormatSchema(),
                "options", ollamaOptions()
        );

        JsonNode response;

        try {
            response = webClient.post()
                    .uri(apiURL)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to call Ollama API", e);
            return ModelClassificationResponse.invalid("OLLAMA_API_ERROR: " + e.getMessage());
        }

        if (response == null) {
            return ModelClassificationResponse.invalid("NULL_RESPONSE");
        }

        String content = response
                .path("message")
                .path("content")
                .asText();

        if (content == null || content.isBlank()) {
            return ModelClassificationResponse.invalid(response.toString());
        }

        return parseClassification(content);
    }

    private String buildUserMessage(String modelInput) {
        return """
                Classify this BGL log template.
                The dataset label is not included.
                Return only {"label":"0"} or {"label":"1"}.
                
                %s
                """.formatted(modelInput);
    }

    private Map<String, Object> jsonFormatSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "label", Map.of(
                                "type", "string",
                                "enum", List.of("0", "1")
                        )
                ),
                "required", List.of("label"),
                "additionalProperties", false
        );
    }

    private Map<String, Object> ollamaOptions() {
        return Map.of(
                "temperature", temperature,
                "top_p", topP,
                "repeat_penalty", repeatPenalty,
                "seed", seed,
                "num_ctx", numCtx,
                "num_predict", numPredict
        );
    }

    private ModelClassificationResponse parseClassification(String content) {
        String normalizedContent = extractJsonObject(content);

        try {
            JsonNode json = objectMapper.readTree(normalizedContent);
            String label = json.path("label").asText();

            if ("0".equals(label) || "1".equals(label)) {
                return ModelClassificationResponse.valid(label, content);
            }

            return ModelClassificationResponse.invalid(content);

        } catch (Exception e) {
            log.error("Failed to parse Ollama response content: {}", content, e);
            return ModelClassificationResponse.invalid(content);
        }
    }

    private String extractJsonObject(String content) {
        String trimmed = content.trim();

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }
}
