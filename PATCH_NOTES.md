# LLMLogAnalyzer V11 Chart Fix

This patch updates only `EvaluationChartService.java`.

## Fixes

1. Removes duplicated `Cache Hits` bar from `final_decision_sources.png`.
   - `Cache Hits` is the same line-level count as `decisionSource=TEMPLATE_CACHE`, so showing both as separate bars is misleading.
   - Cache hit rate is now shown in the chart subtitle.

2. Fixes clipped labels in `Final Proposed Method - Main Evaluation Metrics`.
   - Ratio charts now get a small headroom above the maximum value.
   - Item labels are explicitly positioned above bars.

3. Keeps MongoDB-side metrics behavior unchanged.
   - This patch does not load all Mongo records into memory.

## Expected Decision Source Chart

The chart now shows only:

- LLM
- Cache
- Guard

The subtitle shows:

- Cache Hit Rate
- LLM Rate
- Guard Rate
