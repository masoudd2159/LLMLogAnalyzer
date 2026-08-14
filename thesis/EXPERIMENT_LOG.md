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

## Scalability evaluation: Hybrid, partial full BGL

- Date: 2026-08-14 (Asia/Tehran)
- Run ID: `a4deff93-5ff9-4518-801f-0f2fa8c059af`
- Status: `STOPPED_PARTIAL` (manual user-requested stop after a complete persisted batch)
- Purpose: large-scale Hybrid scalability evaluation; use the 2k Hybrid/Prompt-only runs for the controlled method comparison
- Source dataset: `BGL.log`, 4,747,963 records
- Processed and persisted: 3,645,000 records (76.7698% of the full file)
- Dataset SHA-256: `666130b15ef44eb32fd02bd053e6c6e007c37696b5e7e8b9d8e45b729876a5d2`
- Mode: Hybrid Guard + LLM + validated template cache
- Model: `qwen2.5:7b-instruct`
- Model digest: `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e`
- Prompt version: `BGL_TEMPLATE_AWARE_FINAL_V18_NODE_MAP_BLOCK_DEVICE`
- Inference settings: temperature 0, top-p 0.1, repeat penalty 1.0, seed 42, num-ctx 4096, num-predict 8
- Ground-truth labels were used only for evaluation, not training or fine-tuning
- Parsed/persisted: 3,645,000; recorded parse errors: 0
- Confusion matrix: TP 297,324, TN 3,344,250, FP 1,348, FN 2,078
- Accuracy: 0.999060082 (99.9060%)
- Precision: 0.995486688 (99.5487%)
- Recall: 0.993059499 (99.3059%)
- F1: 0.994271612 (99.4272%)
- Invalid outputs: 0 (0%)
- Direct-decision records (cache excluded): 54,994
- Direct-decision confusion matrix: TP 2,144, TN 52,413, FP 425, FN 12
- Direct-decision accuracy: 0.992053679 (99.2054%)
- Direct-decision precision: 0.834565979 (83.4566%)
- Direct-decision recall: 0.994434137 (99.4434%)
- Direct-decision F1: 0.907513228 (90.7513%)
- Direct LLM calls: 50,347 (1.3813% of processed records)
- Direct Guard decisions: 4,647 (0.1275%)
- Template-cache hits: 3,590,006 (98.4912%)
- Cache hits originating from LLM: 2,843,650 (78.0151% of all records)
- Cache hits originating from Guard: 746,356 (20.4762% of all records)
- Final cache size: 37,005 templates (32,358 originating from LLM and 4,647 from Guard)
- Non-cached LLM results: 17,989 (35.7300% of direct LLM calls)
- Average direct LLM response: 1,051.70 ms (min 666 ms, max 16,703 ms)
- Total direct LLM wait: 52,950,085 ms (14 h 42 m 30.085 s)
- Total processing duration: 53,354,750 ms (14 h 49 m 14.750 s)
- Throughput: 68.3163 records/s
- Amortized model wait over all records: 14.5268 ms/record

### Scalability bottlenecks observed

- Bare hexadecimal address fields such as `iar 0014a150 dear 009aacd8` were not normalized because they lack a `0x` prefix. They therefore created many one-use cache keys. In a recent 200k-record database sample, the normalized family `iar <HEX_BARE> dear <HEX_BARE>` caused 10,905 LLM calls and 11,485.7 seconds of cumulative inference time.
- Empty `RAS/KERNEL/INFO` messages were intentionally rejected by cache validation. In the same sample, 3,156 empty messages caused 2,738.4 seconds of inference time and all were non-cached.
- The normal repeated message `data cache search parity error detected. attempting to correct` did not match the narrower Guard rule for `d-cache search parity error`. Because validation treats unmatched parity-error messages as cache-sensitive, repeated occurrences were sent independently to the LLM.
- These findings explain the long runtime and are retained as implementation limitations and future optimization targets. The run was stopped after a complete 1,000-record persistence boundary; the final interrupted Ollama request was not included in the 3,645,000 persisted evaluations.

### Reporting constraint

This run must be described as a partial large-scale or scalability evaluation over 3,645,000 BGL records, not as a completed evaluation of all 4,747,963 records. The controlled Hybrid-versus-Prompt-only comparison remains the matched 2,000-record experiment.
