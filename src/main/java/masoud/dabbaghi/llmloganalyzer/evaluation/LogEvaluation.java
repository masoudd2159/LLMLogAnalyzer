package masoud.dabbaghi.llmloganalyzer.evaluation;

import lombok.*;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
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

    private String log;

    // Ground Truth from dataset
    private ClassificationResult realResult;

    private LogType logType;

    private String prompt;

    // Prediction from AI
    private ClassificationResult aiResult;

    private AiModel aiModel;

    private Boolean correct;

    private Long responseTimeMs;

    private LocalDateTime createdAt;

    // Optional but useful for experiments
    private String rawModelOutput;
}