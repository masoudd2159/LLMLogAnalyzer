package masoud.dabbaghi.llmloganalyzer.evaluation;

import lombok.*;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "log_evaluations")
public class LogEvaluation {

    @Id
    private String id;

    /*
     * Original raw dataset log.
     * This is stored only for traceability.
     * It must NOT be sent directly to the model because it contains the dataset label.
     */
    private String log;

    /*
     * Model input after removing dataset label.
     * In the template-cache method this contains the normalized template plus one label-free example.
     */
    private String modelInput;

    /*
     * Original BGL dataset label.
     * "-" means normal.
     * Any other value means anomaly.
     */
    private String datasetLabel;

    /*
     * Ground truth generated from datasetLabel.
     */
    private ClassificationResult realResult;

    /*
     * Final prediction.
     * Can be NORMAL, ANOMALY, or INVALID.
     */
    private ClassificationResult aiResult;

    private LogType logType;
    private AiModel aiModel;

    /*
     * Shows whether the result came from TEMPLATE_GUARD, LLM, or TEMPLATE_CACHE.
     */
    private BglDecisionSource decisionSource;

    /*
     * If decisionSource = TEMPLATE_CACHE, cacheSource stores the original source of the cached decision.
     * For example: LLM or TEMPLATE_GUARD.
     */
    private BglDecisionSource cacheSource;

    /*
     * If decisionSource = TEMPLATE_GUARD, this stores the matched deterministic template name.
     * If decisionSource = TEMPLATE_CACHE, this can store the cached matched rule if one existed.
     */
    private String matchedTemplatePattern;

    /*
     * Template metadata used for cache lookup and thesis analysis.
     */
    private String templateKey;
    private String normalizedTemplate;
    private Boolean cacheHit;
    private Boolean cacheable;

    /*
     * Validation metadata.
     * If an LLM result is suspicious, it is saved for the current line but not cached.
     */
    private String validationStatus;
    private String validationReason;

    /*
     * Prompt experiment metadata.
     */
    private PromptExperiment promptExperiment;
    private String promptVersion;
    private String prompt;

    /*
     * Model output audit fields.
     * For template guard predictions, rawModelOutput is stored as:
     * TEMPLATE_GUARD:<matchedTemplatePattern>
     * For cache hits, rawModelOutput is stored as:
     * TEMPLATE_CACHE:<originalDecisionSource>:<cachedRawModelOutput>
     */
    private String rawModelOutput;
    private Boolean validModelOutput;

    /*
     * correct is false for INVALID predictions.
     */
    private Boolean correct;

    /*
     * LLM inference time in milliseconds.
     * Template-guard and template-cache predictions use 0 ms because the LLM is not called.
     */
    private Long responseTimeMs;

    private LocalDateTime createdAt;
}
