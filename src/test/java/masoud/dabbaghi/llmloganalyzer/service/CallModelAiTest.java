package masoud.dabbaghi.llmloganalyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import masoud.dabbaghi.llmloganalyzer.config.OllamaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallModelAiTest {

    private CallModelAi client;

    @BeforeEach
    void setUp() {
        client = new CallModelAi(
                WebClient.builder().baseUrl("http://127.0.0.1:11434").build(),
                new ObjectMapper(),
                officialProperties()
        );
    }

    @Test
    void acceptsStrictJson() {
        ModelClassificationResponse response = client.parseClassification(
                "{\"prediction\":\"anomaly\",\"confidence\":0.98,\"reason\":\"Explicit storage failure.\",\"category\":\"storage\"}"
        );

        assertTrue(response.valid());
        assertEquals("anomaly", response.prediction());
        assertEquals(0.98, response.confidence());
    }

    @Test
    void acceptsWhitespaceAndMarkdownFence() {
        ModelClassificationResponse response = client.parseClassification(
                "  ```json\n{\"prediction\":\"normal\",\"confidence\":1.0,\"reason\":\"Diagnostic register only.\",\"category\":\"diagnostic\"}\n```  "
        );

        assertTrue(response.valid());
        assertEquals("normal", response.prediction());
    }

    @Test
    void rejectsIncompleteJson() {
        ModelClassificationResponse response = client.parseClassification(
                "{\"prediction\":\"normal\",\"confidence\":1.0"
        );

        assertFalse(response.valid());
        assertTrue(response.validationError().startsWith("Output is not complete valid JSON"));
    }

    @Test
    void rejectsTextOutsideJson() {
        ModelClassificationResponse response = client.parseClassification(
                "Answer: {\"prediction\":\"normal\",\"confidence\":1.0,\"reason\":\"Diagnostic.\",\"category\":\"diagnostic\"}"
        );

        assertFalse(response.valid());
    }

    @Test
    void rejectsMissingOrExtraFields() {
        assertFalse(client.parseClassification(
                "{\"prediction\":\"normal\",\"confidence\":1.0,\"reason\":\"Diagnostic.\"}"
        ).valid());
        assertFalse(client.parseClassification(
                "{\"prediction\":\"normal\",\"confidence\":1.0,\"reason\":\"Diagnostic.\",\"category\":\"diagnostic\",\"extra\":true}"
        ).valid());
    }

    @Test
    void rejectsInvalidConfidenceAndCategory() {
        assertFalse(client.parseClassification(
                "{\"prediction\":\"normal\",\"confidence\":1.1,\"reason\":\"Diagnostic.\",\"category\":\"diagnostic\"}"
        ).valid());
        assertFalse(client.parseClassification(
                "{\"prediction\":\"normal\",\"confidence\":0.9,\"reason\":\"Diagnostic.\",\"category\":\"other\"}"
        ).valid());
    }

    @Test
    void sendsFrozenQwenPayloadAndCapturesUsage() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("""
                    {"model":"qwen3.5:35b","done":true,"done_reason":"stop",
                    "prompt_eval_count":321,"eval_count":27,"total_duration":9000,"load_duration":1000,
                    "message":{"role":"assistant","content":"{\\"prediction\\":\\"anomaly\\",\\"confidence\\":0.99,\\"reason\\":\\"Explicit storage interrupt.\\",\\"category\\":\\"storage\\"}"}}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OllamaProperties properties = officialProperties();
            ObjectMapper mapper = new ObjectMapper();
            CallModelAi testClient = new CallModelAi(
                    WebClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .build(),
                    mapper,
                    properties
            );

            ModelClassificationResponse response = testClient.classifyWithOllama(
                    "MESSAGE_TEMPLATE: data storage interrupt",
                    "Return the required object."
            );
            assertTrue(response.valid());
            assertEquals(321, response.promptTokenCount());
            assertEquals(27, response.outputTokenCount());

            var request = mapper.readTree(capturedBody.get());
            assertEquals("qwen3.5:35b", request.path("model").asText());
            assertFalse(request.path("stream").asBoolean());
            assertFalse(request.path("think").asBoolean());
            assertEquals(0.0, request.path("options").path("temperature").asDouble());
            assertEquals(0.9, request.path("options").path("top_p").asDouble());
            assertEquals(42, request.path("options").path("seed").asInt());
            assertEquals(8192, request.path("options").path("num_ctx").asInt());
            assertEquals(160, request.path("options").path("num_predict").asInt());
            assertFalse(request.path("format").path("additionalProperties").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesExactModelDigestFromOllamaTags() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] response = """
                    {"models":[{"name":"qwen3.5:35b","model":"qwen3.5:35b","digest":"sha256:research-model"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OllamaProperties properties = officialProperties();
            CallModelAi testClient = new CallModelAi(
                    WebClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .build(),
                    new ObjectMapper(),
                    properties
            );

            assertEquals("sha256:research-model", testClient.resolveModelVersion());
        } finally {
            server.stop(0);
        }
    }

    private OllamaProperties officialProperties() {
        OllamaProperties properties = new OllamaProperties();
        properties.setBaseUrl("http://127.0.0.1:11434");
        properties.setChatPath("/api/chat");
        properties.setModelName("qwen3.5:35b");
        properties.setModelVersion("AUTO");
        properties.setFormat("json");
        properties.setThinking(false);
        properties.setKeepAlive("30m");
        properties.getOptions().setTemperature(0.0);
        properties.getOptions().setTopP(0.9);
        properties.getOptions().setRepeatPenalty(1.0);
        properties.getOptions().setSeed(42);
        properties.getOptions().setNumCtx(8192);
        properties.getOptions().setNumPredict(160);
        properties.getTimeouts().setConnect(Duration.ofSeconds(10));
        properties.getTimeouts().setResponse(Duration.ofMinutes(15));
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoff(Duration.ofSeconds(2));
        properties.getRetry().setMaxBackoff(Duration.ofSeconds(15));
        return properties;
    }
}
