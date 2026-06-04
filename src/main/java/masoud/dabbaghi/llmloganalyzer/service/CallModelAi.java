package masoud.dabbaghi.llmloganalyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class CallModelAi {

    private final WebClient webClient;

    public String getOllamaResult(LogBglEntryDto dto, String model, String prompt, String apiURL) {
        return getFirstResponseLine(dto.getMainLog(), prompt, apiURL, model);
    }

    public String getFirstResponseLine(
            String mainLog,
            String prompt,
            String apiURL,
            String model
    ) {

        Map<String, Object> request = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", prompt),
                        Map.of("role", "user", "content", mainLog)
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
                        "repeat_penalty ", 1.0,
                        "do_sample  ", false
                )
        );

        JsonNode response = webClient.post()
                .uri(apiURL)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        try {
            String content = response
                    .path("message")
                    .path("content")
                    .asText();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(content);

            return json.path("label").asText();

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Ollama response", e);
        }
    }
}
