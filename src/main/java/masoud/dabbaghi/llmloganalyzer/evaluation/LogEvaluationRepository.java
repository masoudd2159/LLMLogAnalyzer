package masoud.dabbaghi.llmloganalyzer.evaluation;

import masoud.dabbaghi.llmloganalyzer.entity.AiModel;
import masoud.dabbaghi.llmloganalyzer.entity.LogType;
import masoud.dabbaghi.llmloganalyzer.service.PromptExperiment;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogEvaluationRepository extends MongoRepository<LogEvaluation, String> {

    List<LogEvaluation> findByLogTypeAndAiModelAndPromptExperimentAndPromptVersion(
            LogType logType,
            AiModel aiModel,
            PromptExperiment promptExperiment,
            String promptVersion
    );

    List<LogEvaluation> findByLogTypeAndAiModel(
            LogType logType,
            AiModel aiModel
    );
}