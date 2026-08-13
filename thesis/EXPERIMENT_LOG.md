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

## Development validation: Hybrid V18, BGL 2k

- Date: 2026-08-14 (Asia/Tehran)
- Run ID: `ccb9d4a4-ec08-4cda-bff1-3a97da1f1fa6`
- Status: completed
- Purpose: confirm the pilot-derived correction before freezing the full experiment
- Dataset: `BGL_2k.log`
- Dataset SHA-256: `2a819ea540909db682005c9cf948387a40729b5c2e9f19d430e29ce704825496`
- Mode: Hybrid Guard + LLM + template cache
- Model: `qwen2.5:7b-instruct`, Q4_K_M, 7.6B
- Model digest: `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e`
- Prompt version: `BGL_TEMPLATE_AWARE_FINAL_V18_NODE_MAP_BLOCK_DEVICE`
- Inference settings: temperature 0, top-p 0.1, repeat penalty 1.0, seed 42, num-ctx 4096, num-predict 8
- Parsed: 2,000 / 2,000; parse errors: 0
- Confusion matrix: TP 143, TN 1,857, FP 0, FN 0
- Accuracy: 1.000000
- Precision: 1.000000
- Recall: 1.000000
- F1: 1.000000
- Invalid output rate: 0
- Direct-decision confusion matrix (cache excluded): TP 28, TN 300, FP 0, FN 0
- Direct LLM decisions: 256 (TP 4, TN 252)
- Direct Guard decisions: 72 (TP 24, TN 48)
- Cache decisions: 1,672 (TP 115, TN 1,557)
- Cache hits: 83.60%; 1,375 originated from LLM decisions and 297 from Guard decisions
- Final cache size: 315 templates
- Non-cached LLM results: 13; all were valid and correct normal predictions
- Average direct LLM response: 1,080.02 ms (min 683 ms, max 8,461 ms)
- Processing duration: 276,852 ms
- Throughput: 7.2241 lines/s

The previous `Block device required` false positive was classified correctly by the new deterministic normal rule. No additional error was observed on this development subset. This perfect 2k result is a validation result, not evidence that the full BGL evaluation will also be perfect.

## Development comparison: Prompt-only V2, BGL 2k

- Date: 2026-08-14 (Asia/Tehran)
- Run ID: `c2c6b5c3-378e-450a-8c99-cdd5f948d594`
- Status: completed
- Purpose: compare the frozen Prompt-only route with Hybrid V18 on the same development subset
- Dataset: `BGL_2k.log`
- Dataset SHA-256: `2a819ea540909db682005c9cf948387a40729b5c2e9f19d430e29ce704825496`
- Mode: Prompt-only LLM + template cache; deterministic Guard disabled
- Model: `qwen2.5:7b-instruct`, Q4_K_M, 7.6B
- Model digest: `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e`
- Prompt version: `BGL_PROMPT_ONLY_GUARD_RULES_EMBEDDED_V2_NODE_MAP_BLOCK_DEVICE`
- Inference settings: temperature 0, top-p 0.1, repeat penalty 1.0, seed 42, num-ctx 4096, num-predict 8
- Parsed: 2,000 / 2,000; parse errors: 0
- Confusion matrix: TP 139, TN 1,857, FP 0, FN 4
- Accuracy: 0.998000
- Precision: 1.000000
- Recall: 0.972028
- F1: 0.985816
- Invalid output rate: 0
- Direct-decision confusion matrix (cache excluded): TP 24, TN 289, FP 0, FN 4
- Direct-decision accuracy: 0.987382
- Direct-decision precision: 1.000000
- Direct-decision recall: 0.857143
- Direct-decision F1: 0.923077
- Direct LLM calls: 317
- Cache decisions: 1,683 (TP 115, TN 1,568)
- Cache hits: 84.15%; all originated from LLM decisions
- Final cache size: 317 templates
- Non-cached LLM results: 0
- Average direct LLM response: 1,146.04 ms (min 1,068 ms, max 9,844 ms)
- Processing duration: 363,587 ms
- Throughput: 5.5007 lines/s

All four false negatives were direct LLM decisions from the same event family:

`Error receiving packet on tree network, expecting type ... instead of type ...`

The prompt explicitly classifies this event family as anomalous, but the model returned normal for four parameterized variants. The predictions were not copied from cache because their unprefixed hexadecimal fields produced distinct normalized templates. Hybrid V18 classified the same four records correctly through its deterministic Guard.

No further rule or prompt tuning is performed from these errors. V18/V2 are frozen after the planned development comparison to avoid repeatedly adapting the method to labeled development records.

## BGL 2k development comparison summary

| Measure | Hybrid V18 | Prompt-only V2 |
|---|---:|---:|
| Accuracy | 1.000000 | 0.998000 |
| Precision | 1.000000 | 1.000000 |
| Recall | 1.000000 | 0.972028 |
| F1 | 1.000000 | 0.985816 |
| Invalid rate | 0 | 0 |
| Direct LLM calls | 256 | 317 |
| Direct Guard decisions | 72 | 0 |
| Cache hits | 1,672 | 1,683 |
| Processing duration (ms) | 276,852 | 363,587 |
| Throughput (lines/s) | 7.2241 | 5.5007 |

On this subset, Hybrid required 19.24% fewer direct LLM calls, reduced total processing time by 23.86%, and increased throughput by 31.33% relative to Prompt-only. These are development-set observations; the full frozen evaluation is required for thesis claims.

## Frozen final evaluation set

- Source dataset: `BGL.log`
- Source line count: 4,747,963
- Development exclusion: `BGL_2k.log`
- Development line count: 2,000
- Development SHA-256: `2a819ea540909db682005c9cf948387a40729b5c2e9f19d430e29ce704825496`
- Exact development lines found in source: 2,000
- Missing development lines: 0
- Ambiguous extra duplicate matches: 0
- Final evaluation line count: 4,745,963
- Logical final-evaluation SHA-256: `9bc320945c5edbd0830649b4d51c852f9684ec35bfb9dcc3885b95b9ea50a738`

The logical evaluation digest is calculated over every retained UTF-8 line followed by LF, in original source order. The source file remains unchanged. The program performs the exact-multiplicity preflight and holdout hashing before any LLM call, then excludes the same lines during processing and aborts if the source changes between the two passes.
