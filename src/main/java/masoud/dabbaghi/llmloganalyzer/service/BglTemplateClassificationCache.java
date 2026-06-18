package masoud.dabbaghi.llmloganalyzer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory cache for template-level BGL classifications.
 * <p>
 * It is intentionally in-memory because the experiment run processes a dataset in one JVM.
 * MongoDB still receives one LogEvaluation per raw log, so final metrics remain line-level.
 */
@Service
@Slf4j
public class BglTemplateClassificationCache {

    private final ConcurrentMap<String, BglCachedClassification> cache = new ConcurrentHashMap<>();

    public Optional<BglCachedClassification> find(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            return Optional.empty();
        }

        BglCachedClassification cached = cache.get(templateKey);
        if (cached != null) {
            cached.incrementHitCount();
        }
        return Optional.ofNullable(cached);
    }

    public void putIfCacheable(BglCachedClassification classification) {
        if (classification == null || !classification.isCacheable()) {
            return;
        }

        BglCachedClassification existing = cache.putIfAbsent(
                classification.getTemplateKey(),
                classification
        );

        if (existing == null) {
            log.debug(
                    "Cached BGL template result: prediction={}, source={}, template={}",
                    classification.getPrediction(),
                    classification.getOriginalDecisionSource(),
                    classification.getNormalizedTemplate()
            );
        }
    }

    public int size() {
        return cache.size();
    }
}
