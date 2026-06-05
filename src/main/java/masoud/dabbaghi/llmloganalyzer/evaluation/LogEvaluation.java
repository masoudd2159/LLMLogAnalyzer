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
     * Prediction from AI.
     * Can be NORMAL, ANOMALY, or INVALID.
     */
    private ClassificationResult aiResult;

    private LogType logType;
    private AiModel aiModel;

    /*
     * Prompt experiment metadata.
     */
    private PromptExperiment promptExperiment;
    private String promptVersion;
    private String prompt;

    /*
     * Model output audit fields.
     */
    private String rawModelOutput;
    private Boolean validModelOutput;

    /*
     * correct is false for INVALID predictions.
     */
    private Boolean correct;

    private Long responseTimeMs;

    private LocalDateTime createdAt;
}