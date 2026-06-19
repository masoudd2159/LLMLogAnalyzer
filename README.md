# LLMLogAnalyzer

**Improving Anomaly Detection in System Logs Based on Large Language Models Using Template-Aware Prompt Engineering and
Result Caching**

LLMLogAnalyzer is a Java Spring Boot research project developed for a Master's thesis. The project investigates how
Large Language Models can be used for binary anomaly detection in BGL system logs while reducing inference cost through
template-level reuse.

The current research focus is a **template-aware hybrid method**:

```text
Template Extraction + Template Cache + Deterministic Guard + Validated LLM Inference
```

Repeated BGL log patterns are normalized into stable templates. Each unique template is classified once when possible,
and later matching logs reuse the stored decision. Suspicious LLM predictions are not cached, which helps reduce error
propagation.

---

## Research Objective

The objective of this project is to evaluate whether a Large Language Model can classify BGL log entries as normal or
anomalous when it is guided by a template-aware prompt and supported by deterministic rules, template caching, and cache
validation.

The method focuses on:

- BGL alert / non-alert classification;
- removing the original dataset label before inference;
- template extraction from raw log messages;
- deterministic classification of high-confidence templates;
- LLM inference only for new or ambiguous templates;
- validation before caching LLM predictions;
- strict JSON output generation;
- local model execution through Ollama;
- evaluation using standard classification metrics;
- runtime reduction through template-level caching;
- comparison with selected baseline work from the literature.

---

## Dataset

This project uses the **BGL Blue Gene/L** log dataset.

Each BGL log entry starts with an original dataset label:

| Dataset Label   | Meaning            |
|-----------------|--------------------|
| `-`             | Normal / non-alert |
| any other value | Anomaly / alert    |

The original dataset label is **not provided to the model** during inference. It is removed before prediction and is
used only after prediction for evaluation.

---

## Classification Task

Each log entry must be classified as:

```text
0 = normal / non-alert
1 = anomaly / alert
```

The model output must be exactly one JSON object:

```json
{"label":"0"}
```

or:

```json
{"label":"1"}
```

Invalid or malformed outputs are tracked separately.

---

## Proposed Method

The proposed method is no longer a pure line-by-line LLM approach. It uses template-level optimization:

```text
Raw BGL log
   ↓
Parse label-free fields
   ↓
Normalize message into a stable template
   ↓
Check template cache
   ↓
If cached: reuse previous prediction
   ↓
If not cached: apply BglTemplateGuard
   ↓
If guard is confident: save deterministic prediction
   ↓
If ambiguous: send normalized template + example log to LLM
   ↓
Validate LLM prediction before caching
   ↓
Save LogEvaluation record
```

### Why this is useful

The BGL dataset contains many repeated log patterns. Sending every raw line to the LLM is slow and expensive. By
normalizing logs into templates, the system can classify each unique pattern once and reuse the result for repeated
occurrences.

Example:

```text
2005-12-11 R63-M1-N0-I:J18-U11 ciod: LOGIN chdir(/p/gb1/stella/RAPTOR/2183) failed: Input/output error
```

becomes:

```text
ciod: LOGIN chdir(<PATH>) failed: Input/output error
```

If this template appears again, the cached decision can be reused.

---

## Error Propagation Control

A common risk of template caching is that a wrong LLM answer may be reused for many similar logs. To reduce this risk,
the project includes `BglTemplateValidationService`.

The validator does not change the current prediction. Instead, it decides whether the prediction is safe enough to
cache.

Example:

```text
LLM says: NORMAL
Template contains: ciod: LOGIN chdir(...) failed: Input/output error
Validation: SUSPICIOUS_NOT_CACHED
```

The result is still stored for the current line, but it is not reused forever through the cache.

The validator also blocks caching for known normal BGL templates when the LLM predicts anomaly. This is important
because many BGL normal logs contain severe-looking words such as `FATAL`, `error`, `interrupt`, `parity`, or `failed`.

---

## Deterministic Template Guard

`BglTemplateGuard` handles high-confidence known templates before calling the LLM.

Examples of anomaly-style templates:

- `data TLB error interrupt`
- `kernel terminated`
- `kernel panic`
- `Lustre mount FAILED`
- `failed to read message prefix on control stream`
- `control stream closed unexpectedly`
- `Error receiving packet on tree network`
- `ciod: Error creating node map ... No child processes`
- `ciod: LOGIN chdir(...) failed: Input/output error`

Examples of known-normal templates:

- `ciod: Error loading ... invalid or missing program image ... No such file or directory`
- `ciod: Error loading ... Permission denied`
- `ciod: LOGIN chdir(...) failed: No such file or directory`
- `ciod: LOGIN chdir(...) failed: Permission denied`
- `program interrupt: privileged instruction`
- `data store interrupt caused by dcbf`
- `rts: bad message header`
- `detected and corrected`
- `ddr: excessive soft failures, consider replacing the card`
- `instruction plb error ... <NUM>`
- `data read/write plb error ... <NUM>`
- `tlb error ... <NUM>`
- `i-cache parity error ... <NUM>`
- `d-cache parity error ... <NUM>`
- `critical input interrupt enable ... <NUM>`
- standalone hexadecimal/register dump lines

The guard reduces unnecessary LLM calls and improves consistency for frequent patterns.

---

## Model

The project is designed to work with local Ollama models.

Example configuration:

```properties
model.api.ollama.url=http://localhost:11434/api/chat
model.api.ollama.model-name=qwen2.5:7b-instruct
```

Recommended evaluation approach:

```text
Same dataset
Same prompt/cache/guard settings
Different model
```

This makes it possible to compare how model capacity affects accuracy, precision, recall, F1-score, and response time.

---

## System Architecture

```text
src/main/java/masoud/dabbaghi/llmloganalyzer
│
├── config
│   ├── ChartRunner.java
│   ├── OpenAIConfig.java
│   └── WebClientConfiguration.java
│
├── controller
│   └── BglController.java
│
├── dto
│   └── LogBglEntryDto.java
│
├── evaluation
│   ├── BglDecisionSource.java
│   ├── ClassificationResult.java
│   ├── EvaluationMetrics.java
│   ├── EvaluationMetricsService.java
│   ├── LogEvaluation.java
│   ├── LogEvaluationRepository.java
│   └── LogEvaluationService.java
│
├── service
│   ├── BglParser.java
│   ├── BglTemplate.java
│   ├── BglTemplateExtractor.java
│   ├── BglTemplateGuard.java
│   ├── BglTemplateClassificationCache.java
│   ├── BglCachedClassification.java
│   ├── BglTemplateValidationService.java
│   ├── BglTemplateValidationResult.java
│   ├── CallModelAi.java
│   ├── ModelClassificationResponse.java
│   ├── PromptExperiment.java
│   ├── PromptGenerator.java
│   └── PromptSpec.java
│
└── visualization
    └── EvaluationChartService.java
```

---

## Main Components

### BglParser

Reads BGL logs, parses each line, removes the original dataset label, extracts a normalized template, checks the
template cache, applies the deterministic guard, calls the LLM only when needed, validates cacheability, and stores the
final evaluation result.

### BglTemplateExtractor

Normalizes runtime-specific values such as node ids, paths, hex values, dates, timestamps, and numbers while preserving
semantic words such as `corrected`, `uncorrected`, `failed`, `terminated`, and `Input/output error`.

### BglTemplateGuard

Classifies high-confidence known templates without calling the LLM. It is intentionally conservative and avoids broad
keyword-only rules.

### BglTemplateValidationService

Prevents suspicious LLM decisions from entering the cache. This reduces the risk of repeating one wrong LLM answer
across many matching templates.

### PromptGenerator

Contains the final template-aware prompt used when a template is new and ambiguous.

### EvaluationMetricsService

Calculates the evaluation metrics using MongoDB-side aggregation/counting so the project can handle large BGL evaluation
results without loading all records into memory.

Metrics include:

- Accuracy
- Precision
- Recall
- F1-score
- TP
- TN
- FP
- FN
- Invalid output rate
- Average line response time
- Average LLM-only response time
- Decision source counts
- Template cache size

### EvaluationChartService

Generates thesis-ready charts for result analysis.

---

## Technologies Used

- Java 17
- Spring Boot
- Maven
- MongoDB
- Ollama
- JFreeChart
- Spring WebFlux
- Spring Data MongoDB

---

## How to Run

### 1. Clone the Repository

```bash
git clone https://github.com/masoudd2159/LLMLogAnalyzer.git
cd LLMLogAnalyzer
```

### 2. Run Ollama

```bash
ollama pull qwen2.5:7b-instruct
ollama serve
```

### 3. Configure the Project

Edit:

```text
src/main/resources/application.properties
```

Example:

```properties
spring.application.name=LLMLogAnalyzer
server.port=8081

spring.data.mongodb.host=127.0.0.1
spring.data.mongodb.port=27017
spring.data.mongodb.database=LLMLogAnalyzer

model.api.ollama.url=http://localhost:11434/api/chat
model.api.ollama.model-name=qwen2.5:7b-instruct

bgl.location=D:/Programming/Thesis/Dataset/BGL/BGL.log
hdfs.location=D:/Programming/Thesis/Dataset/HDFS_v1/HDFS.log

bgl.classification.template-cache.enabled=true
bgl.classification.template-guard.enabled=true
bgl.classification.cache-only-validated-llm-results=true
bgl.classification.template-key.include-metadata=false

charts.data.scope=current
charts.output-dir=.
```

### 4. Run MongoDB

```bash
mongod
```

### 5. Clear Old Results

Before a new experiment, clear previous evaluation records so the charts represent one clean run:

```javascript
db.log_evaluations.deleteMany({})
```

Restart the application before a full rerun so the in-memory template cache is rebuilt from zero.

### 6. Run the Application

```bash
mvn spring-boot:run
```

---

## Evaluation Metrics

| Metric        | Description                                     |
|---------------|-------------------------------------------------|
| Accuracy      | Overall classification correctness              |
| Precision     | Reliability of anomaly predictions              |
| Recall        | Ability to detect real anomalies                |
| F1-score      | Balance between precision and recall            |
| TP            | Anomalies correctly detected                    |
| TN            | Normal logs correctly detected                  |
| FP            | Normal logs incorrectly classified as anomalies |
| FN            | Anomalies missed by the model                   |
| Invalid Rate  | Invalid or malformed model outputs              |
| Response Time | Average inference time per log entry            |

---

## Result Charts

The following charts are generated after running the experiment. The README includes the chart images directly, while
the exact values are read from the generated images and MongoDB evaluation records.

### Main Evaluation Metrics

![Final Metrics](final_metrics.png)

### Confusion Matrix

![Final Confusion Matrix](final_confusion_matrix.png)

### Invalid Output Rate

![Final Invalid Output Rate](final_invalid_rate.png)

### Average Response Time

![Final Average Response Time](final_response_time.png)

### Decision Sources

![Final Decision Sources](final_decision_sources.png)

### Template Cache Size by Source

![Final Template Cache Size](final_template_cache_size.png)

---

## Runtime Optimization

The proposed method reduces direct LLM usage by combining deterministic guard decisions and template-level caching. In
large BGL runs, most line-level decisions are expected to come from the template cache rather than direct model calls.

The decision source chart shows how many predictions came from:

- direct LLM calls;
- cached LLM decisions;
- direct deterministic guard decisions;
- cached deterministic guard decisions.

The response-time chart separates average line-level response time from average LLM-only response time. This makes the
runtime advantage of caching visible without manually hard-coding result numbers in the README.

---

## Error Analysis

In BGL logs, many normal messages contain severe-looking words. For example, some normal / non-alert messages may
include:

- `FATAL`
- `Error`
- `failed`
- `exception`
- `interrupt`
- `parity`
- `uncorrectable`

This can cause false positives if the model relies on keywords instead of message templates.

The final prompt and guard therefore emphasize:

```text
Template meaning is more important than severity words.
```

False-positive-prone normal templates are handled by the guard and validator, including diagnostic counter lines, parity
counter lines, register dump lines, and permission/file-path failures that do not indicate unrecovered system impact.

The opposite issue can also happen. Some templates look like normal file/path problems but are actually anomaly-style
BGL labels. For example:

```text
ciod: LOGIN chdir(...) failed: Input/output error
```

This is handled by the guard and validation logic as a high-confidence anomaly signal.

---

## Baseline Comparison Plan

The final thesis should compare the proposed method with a selected baseline paper.

Recommended baseline:

```text
Exploring ChatGPT for Log-Based Anomaly Detection
```

The comparison can be reported as:

| Method          | Dataset | Model             | Accuracy | Precision |   Recall |       F1 | LLM Calls / Runtime |
|-----------------|---------|-------------------|---------:|----------:|---------:|---------:|--------------------:|
| Baseline paper  | BGL     | Reported in paper |     Fill |      Fill |     Fill |     Fill |                Fill |
| Proposed method | BGL     | Local Ollama LLM  | From run |  From run | From run | From run |            From run |

Important note: the comparison should clearly state whether the reported result is based on the full dataset, a subset,
or a specific experimental split.

---

## Thesis Notes

Important points:

- The original BGL label is removed from the model input.
- Ground truth is used only after prediction.
- The current method is template-aware and hybrid.
- The LLM is called only for new or ambiguous templates.
- Guard decisions are deterministic and explainable.
- LLM decisions are validated before being cached.
- Suspicious LLM outputs can be saved for the current line without being reused forever.
- The result charts should be regenerated after each clean experiment.
- For final reporting, keep one clean MongoDB evaluation collection per experiment or explicitly document the chart
  scope.
- A stronger model can be tested while keeping dataset, prompt, cache, and guard settings fixed.
- Final reported results should be evaluated on a held-out test set, full dataset run, or a clearly defined dataset
  split.

---

## Author

**Masoud Dabbaghi**

Master's Degree Project  
**Improving Anomaly Detection in System Logs Based on Large Language Models Using Prompt Engineering**
