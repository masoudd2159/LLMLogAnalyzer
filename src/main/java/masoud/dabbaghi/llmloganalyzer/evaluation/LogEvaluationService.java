package masoud.dabbaghi.llmloganalyzer.evaluation;

import lombok.RequiredArgsConstructor;
import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LogEvaluationService {

    private final LogEvaluationRepository repository;

    public LogEvaluation save(LogEvaluation evaluation) {
        return repository.save(evaluation);
    }

    public List<LogEvaluation> findAll() {
        return repository.findAll();
    }

    public List<LogEvaluation> findByLogTypeAndAiModel(
            LogType logType,
            AiModel aiModel
    ) {
        return repository.findByLogTypeAndAiModel(logType, aiModel);
    }

    public List<LogEvaluation> findByExperiment(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment,
            String promptVersion
    ) {
        return repository.findByLogTypeAndAiModelAndPromptExperimentAndPromptVersion(
                logType,
                aiModel,
                promptExperiment,
                promptVersion
        );
    }

    public void deleteAll() {
        repository.deleteAll();
    }
}