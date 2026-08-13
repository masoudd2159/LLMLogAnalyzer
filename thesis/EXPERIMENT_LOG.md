# Experiment Log

This file separates development/pilot runs from the final thesis evaluation runs.

## Development pilot: Hybrid, BGL 2k

- Date: 2026-08-14 (Asia/Tehran)
- Run ID: `5ab31f7c-6f9b-4be2-852d-b6fdf223732b`
- Status: completed
- Purpose: pipeline validation and error analysis; **not a final reported test result**
- Dataset: `BGL_2k.log`
- Dataset SHA-256: `2a819ea540909db682005c9cf948387a40729b5c2e9f19d430e29ce704825496`
- Mode: Hybrid Guard + LLM + template cache
- Model: `qwen2.5:7b-instruct`, Q4_K_M, 7.6B
- Model digest (confirmed after the run): `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e`
- Prompt version: `BGL_TEMPLATE_AWARE_FINAL_V17_MACHINE_CHECK_FIELDS`
- Inference settings recorded by the run: temperature 0, top-p 0.1, repeat penalty 1.0, seed 42, num-ctx 2048, num-predict 8
- Parsed: 2,000 / 2,000; parse errors: 0
- Confusion matrix: TP 143, TN 1,856, FP 1, FN 0
- Accuracy: 0.999500
- Precision: 0.993056
- Recall: 1.000000
- F1: 0.996516
- Invalid output rate: 0
- Direct-decision confusion matrix (cache excluded): TP 28, TN 299, FP 1, FN 0
- Direct-decision accuracy: 0.996951
- Direct-decision precision: 0.965517
- Direct-decision recall: 1.000000
- Direct-decision F1: 0.982456
- Direct LLM calls: 257
- Direct Guard decisions: 71
- Cache hits: 1,672 (83.60%)
- Final cache size: 315 templates
- Non-cached LLM results: 13; all were valid and correct normal predictions
- Average direct LLM response: 1,081.58 ms (min 644 ms, max 8,009 ms)
- Processing duration: 278,384 ms
- Throughput: 7.1843 lines/s

### Pilot finding and resulting change

The only false positive was:

`ciod: Error creating node map from file <PATH>: Block device required`

The BGL ground truth marks this template normal, while the LLM predicted anomaly. The same normal pattern occurs 1,016 times in the full dataset, so caching the pilot prediction could amplify one error into many false positives. After this development-run analysis:

- an exact normal Hybrid Guard rule was added;
- the same distinction was added to the Prompt-only instructions;
- Hybrid prompt version was advanced to `BGL_TEMPLATE_AWARE_FINAL_V18_NODE_MAP_BLOCK_DEVICE`;
- Prompt-only version was advanced to `BGL_PROMPT_ONLY_GUARD_RULES_EMBEDDED_V2_NODE_MAP_BLOCK_DEVICE`.

Because this change was derived from the pilot labels/errors, the 2k pilot is retained only as development evidence. Final metrics must come from new, run-scoped evaluations using the frozen V18/V2 rules.
