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
                        Map.of("role", "user", "content", modelInput)
                ),
                "format", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "label", Map.of(
                                        "type", "string",
                                        "enum", List.of("0", "1")
                                )
                        ),
                        "required", List.of("label")
                ),
                "options", Map.of(
                        "temperature", 0,
                        "top_p", 0.1,
                        "repeat_penalty", 1.0,
                        "seed", 42
                )
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

        try {
            JsonNode json = objectMapper.readTree(content);
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
}