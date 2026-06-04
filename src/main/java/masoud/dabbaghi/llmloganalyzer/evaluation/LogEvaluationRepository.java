package masoud.dabbaghi.llmloganalyzer.evaluation;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LogEvaluationRepository extends MongoRepository<LogEvaluation, String> {
}