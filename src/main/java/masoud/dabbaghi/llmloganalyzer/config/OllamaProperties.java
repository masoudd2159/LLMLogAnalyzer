package masoud.dabbaghi.llmloganalyzer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Single source of truth for the frozen Ollama/Qwen inference configuration.
 */
@Component
@ConfigurationProperties(prefix = "model.api.ollama")
@Getter
@Setter
public class OllamaProperties {

    private String baseUrl;
    private String chatPath;
    private String modelName;
    private String modelVersion;
    private String format;
    private boolean thinking;
    private String keepAlive;
    private final Options options = new Options();
    private final Timeouts timeouts = new Timeouts();
    private final Retry retry = new Retry();

    @Getter
    @Setter
    public static class Options {
        private double temperature;
        private double topP;
        private double repeatPenalty;
        private int seed;
        private int numCtx;
        private int numPredict;
    }

    @Getter
    @Setter
    public static class Timeouts {
        private Duration connect;
        private Duration response;
    }

    @Getter
    @Setter
    public static class Retry {
        private int maxAttempts;
        private Duration initialBackoff;
        private Duration maxBackoff;
    }

    /** Ensures thesis runs cannot silently diverge from the centralized frozen settings. */
    public void validateOfficialExperiment() {
        if (!"qwen3.5:35b".equals(modelName)) {
            throw new IllegalStateException("MODEL_NAME must be qwen3.5:35b for the official experiment");
        }
        if (options.temperature != 0.0 || options.topP != 0.9 || options.repeatPenalty != 1.0 || options.seed != 42) {
            throw new IllegalStateException(
                    "Official settings require TEMPERATURE=0, TOP_P=0.9, REPEAT_PENALTY=1.0, and SEED=42"
            );
        }
        if (thinking) {
            throw new IllegalStateException("THINKING must be false for the anomaly-detection benchmark");
        }
        if (!"json".equalsIgnoreCase(format)) {
            throw new IllegalStateException("FORMAT must be json so the JSON Schema is enforced");
        }
        if (options.numCtx != 8192 || options.numPredict != 160) {
            throw new IllegalStateException("Official settings require NUM_CTX=8192 and NUM_PREDICT=160");
        }
        if (timeouts.connect == null || timeouts.response == null || retry.maxAttempts < 1) {
            throw new IllegalStateException("Ollama timeouts must be configured and MAX_ATTEMPTS must be at least 1");
        }
    }
}
