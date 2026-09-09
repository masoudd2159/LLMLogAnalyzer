package masoud.dabbaghi.llmloganalyzer.evaluation;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BglExperimentRunRepository extends MongoRepository<BglExperimentRun, String> {
    Optional<BglExperimentRun> findByExperimentBatchIdAndMethodOrder(String experimentBatchId, int methodOrder);
}
