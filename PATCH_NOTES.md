# LLMLogAnalyzer V9 Patch

## Main goals

This patch targets the full BGL run problems:

1. Reduce LLM calls.
2. Reduce average response time.
3. Improve deterministic guard coverage.
4. Fix wrong chart/metric generation.
5. Avoid JVM memory crashes during chart generation on millions of MongoDB records.

## What changed

### 1. Better template cache

`BglTemplateExtractor` now normalizes more runtime-only values:

- BGL compute and I/O node ids, including `R63-M1-N0-I:J18-U11`.
- Unix paths inside `chdir(...)` and other message positions.
- IP addresses, hex values, dates, floating numbers, integers, and long alphanumeric ids.

By default, the template cache key now uses the normalized message template only:

```properties
bgl.classification.template-key.include-metadata=false
```

This reduces unnecessary unique templates caused by category/component/severity differences. The metadata is still included in the LLM input.

### 2. Better guard

`BglTemplateGuard` now supports additional high-confidence BGL rules, including:

- control stream closed / broken pipe;
- kernel panic / RTS panic;
- ciod loading with Input/output error;
- chdir Input/output error;
- job/node termination patterns;
- more diagnostic normal templates.

### 3. Better validation

`BglTemplateValidationService` now blocks caching when an LLM prediction conflicts with stronger anomaly/normal signals. This prevents wrong LLM answers from being reused for thousands of matching logs.

### 4. Faster LLM calls

`PromptGenerator` is now V9 with a shorter prompt intended only for ambiguous templates after guard/cache have failed.

`CallModelAi` supports configurable Ollama options:

```properties
model.api.ollama.options.num-ctx=2048
model.api.ollama.options.num-predict=16
model.api.ollama.options.top-k=10
```

### 5. Memory-safe metrics and charts

`EvaluationMetricsService` no longer loads all `log_evaluations` documents into memory. It uses MongoDB-side counts and aggregation through `MongoTemplate`.

`EvaluationChartService` now generates charts from aggregated metrics only and adds:

- `final_decision_sources.png`
- corrected confusion matrix counts
- line-level average response time and LLM-only average response time

## Run steps

After copying this patch onto the project:

```bash
mvn clean test
```

Then clear old results before a new V9 experiment:

```javascript
db.log_evaluations.deleteMany({})
```

Run the parser normally.

For chart-only generation:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=charts
```
