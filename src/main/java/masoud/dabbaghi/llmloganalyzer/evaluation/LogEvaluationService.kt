package masoud.dabbaghi.llmloganalyzer.evaluation

import org.springframework.stereotype.Service

@Service
class LogEvaluationService(private val repository: LogEvaluationRepository) {

    fun save(evaluation: LogEvaluation?) {
        if (evaluation != null) {
            repository.save(evaluation)
        }
    }
}