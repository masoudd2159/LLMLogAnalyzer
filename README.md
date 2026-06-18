# LLMLogAnalyzer

**Improving Anomaly Detection in System Logs Based on Large Language Models Using Template-Aware Prompt Engineering and
Result Caching**

LLMLogAnalyzer is a Java Spring Boot research project developed for a Master's thesis. The project investigates how
Large Language Models can be used for binary anomaly detection in BGL system logs while reducing inference time through
template-level reuse.

The current research focus is a **template-aware hybrid method**:

```text
Template Extraction + Template Cache + Deterministic Guard + Validated LLM Inference
```

This means repeated BGL log patterns are classified once and reused for later matching logs, while suspicious LLM
answers are not cached to prevent error propagation.

---

## Research Objective

The objective of this project is to evaluate whether a Large Language Model can classify BGL log entries as normal or
anomalous when it is guided by a carefully designed template-aware prompt and supported by template-level caching.

The method focuses on:

- BGL alert / non-alert classification;
- template extraction from raw log messages;
- result caching for repeated templates;
- deterministic rules for high-confidence templates;
- validation before caching LLM predictions;
- strict JSON output generation;
- local model execution through Ollama;
- evaluation using standard classification metrics;
- comparison with a selected baseline paper from the literature.

---

## Dataset

This project uses the **BGL Blue Gene/L** log dataset.

Each BGL log entry starts with an original dataset label:

| Dataset Label   | Meaning            |
|-----------------|--------------------|
| `-`             | Normal / non-alert |
| any other value | Anomaly / alert    |

A key point in this project is that the original dataset label is **not provided to the model** during inference. The
label is removed before prediction and is used only after prediction for evaluation.

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
normalizing logs into templates, the system can classify each unique pattern once and reuse the result.

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

The validator does not change the model prediction. Instead, it decides whether the prediction is safe to cache.

Example:

```text
LLM says: NORMAL
Template contains: ciod: LOGIN chdir(...) failed: Input/output error
Validation: SUSPICIOUS_NOT_CACHED
```

The result is still stored for the current line, but it is not reused forever through the cache.

---

## Deterministic Template Guard

`BglTemplateGuard` handles high-confidence known templates before calling the LLM.

Examples of anomaly-style templates:

- `data TLB error interrupt`
- `data storage interrupt`
- `kernel terminated`
- `Lustre mount FAILED`
- `ciod: Error creating node map ... No child processes`
- `ciod: LOGIN chdir(...) failed: Input/output error`

Examples of known-normal templates:

- `ciod: Error loading ... invalid or missing program image ... No such file or directory`
- `ciod: LOGIN chdir(...) failed: No such file or directory`
- `program interrupt: privileged instruction`
- `data store interrupt caused by dcbf`
- `rts: bad message header`
- `detected and corrected`

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

This makes it possible to compare how model capacity affects accuracy, precision, recall, and response time.

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

Reads BGL logs, parses each line, removes the original dataset label, extracts a normalized template, checks
cache/guard, calls the LLM only when needed, validates cacheability, and stores the final evaluation result.

### BglTemplateExtractor

Normalizes runtime-specific values such as node ids, paths, hex values, dates, timestamps, and numbers while preserving
semantic words such as `corrected`, `uncorrected`, `failed`, `terminated`, and `Input/output error`.

### BglTemplateGuard

Classifies high-confidence known templates without calling the LLM.

### BglTemplateValidationService

Prevents suspicious LLM decisions from entering the cache.

### PromptGenerator

Contains the final template-aware prompt used when a template is new and ambiguous.

### EvaluationMetricsService

Calculates:

- Accuracy
- Precision
- Recall
- F1-score
- TP
- TN
- FP
- FN
- Invalid output rate
- Average response time

### EvaluationChartService

Generates thesis-ready colorful charts for result analysis.

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

bgl.location=D:/Programming/Thesis/Dataset/BGL/BGL_2k.log
hdfs.location=D:/Programming/Thesis/Dataset/HDFS_v1/HDFS.log

bgl.classification.template-cache.enabled=true
bgl.classification.template-guard.enabled=true
bgl.classification.cache-only-validated-llm-results=true
```

### 4. Run MongoDB

```bash
mongod
```

### 5. Clear Old Results

Before a new experiment, clear previous evaluation records:

```javascript
db.log_evaluations.deleteMany({})
```

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

The following charts are generated after running the experiment. They are included to visualize model performance
without hard-coding exact numbers in the README.

### Main Evaluation Metrics

![Final Metrics](final_metrics.png)

### Confusion Matrix

![Final Confusion Matrix](final_confusion_matrix.png)

### Invalid Output Rate

![Final Invalid Output Rate](final_invalid_rate.png)

### Average Response Time

![Final Average Response Time](final_response_time.png)

---

## Runtime Optimization Example

A sample BGL_2k run using the template-cache hybrid method produced the following runtime behavior:

```text
Total logs: 2000
LLM calls: 245
Cache hits: 1674
Guard hits: 81
Cache size: 326
Not cached: 0
```

This reduced direct LLM calls by approximately 87.75% compared with sending every line to the model.

---

## Error Analysis

In BGL logs, many normal messages contain severe-looking words. For example, some normal / non-alert messages may
include:

- `FATAL`
- `Error`
- `failed`
- `exception`
- `interrupt`

This can cause false positives if the model relies on keywords instead of message templates.

The final prompt therefore emphasizes:

```text
Template meaning is more important than severity words.
```

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

| Method          | Dataset          | Model                                                     |             Accuracy |            Precision |               Recall |                   F1 |            LLM Calls |
|-----------------|------------------|-----------------------------------------------------------|---------------------:|---------------------:|---------------------:|---------------------:|---------------------:|
| Baseline paper  | BGL              | Reported in paper                                         |      Fill from paper |      Fill from paper |      Fill from paper |      Fill from paper |        Not optimized |
| Proposed method | BGL / BGL subset | Local instruction-tuned LLM + template cache + validation | Fill from experiment | Fill from experiment | Fill from experiment | Fill from experiment | Fill from experiment |

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
- A stronger model can be tested while keeping dataset, prompt, cache, and guard settings fixed.
- Final reported results should be evaluated on a held-out test set or a clearly defined dataset split.

---

## Author

**Masoud Dabbaghi**

Master's Degree Project  
**Improving Anomaly Detection in System Logs Based on Large Language Models Using Prompt Engineering**
