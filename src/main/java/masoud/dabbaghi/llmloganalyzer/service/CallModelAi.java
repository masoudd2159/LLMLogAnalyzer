package masoud.dabbaghi.llmloganalyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
@Slf4j
public class CallModelAi {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                Classify the following BGL log entry.
                
                Follow the system prompt decision order exactly.
                Some BGL messages are known anomalies even if they do not explicitly say
                "unrecoverable", "crashed", or "job killed".
                
                Important examples:
                - "data TLB error interrupt" => 1
                - "data storage interrupt" => 1
                - "failed to read message prefix on control stream" => 1
                - "instruction address: 0x..." => 0
                - "data address: 0x..." => 0
                - "ciod: Error loading ..." => 0
                
                Output only JSON.
                
                BGL_LOG_ENTRY:
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
                "temperature", 0,
                "top_p", 1.0,
                "repeat_penalty", 1.0,
                "seed", 42,
                "num_ctx", 4096,
                "num_predict", 16
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