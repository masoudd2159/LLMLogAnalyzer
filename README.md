# LLMLogAnalyzer

**Template-Aware Hybrid Anomaly Detection for BGL System Logs Using Large Language Models**

LLMLogAnalyzer is a Java Spring Boot research project developed for a Master's thesis. It evaluates a hybrid method for binary anomaly detection in Blue Gene/L (BGL) system logs by combining:

```text
Template Extraction
+ Template Cache
+ Deterministic Guard
+ Validated LLM Inference
```

The method removes the original BGL label before inference, normalizes repeated log patterns into stable textual templates, reuses validated classifications through a cache, applies deterministic rules only to high-confidence patterns, and sends new or ambiguous templates to a local LLM.

---

## Research Objective

The project investigates whether a pretrained language model can detect normal and anomalous BGL log entries without model fine-tuning while reducing runtime through template-level reuse.

The main goals are:

- preserve the semantic meaning of log messages;
- avoid keyword-only anomaly detection;
- reduce repeated LLM calls;
- prevent unsafe LLM decisions from propagating through the cache;
- provide reproducible classification metrics and runtime statistics;
- compare the proposed method with published LLM-based and GPT-based log anomaly detection approaches.

---

## Dataset and Classification Task

The project uses the **BGL Blue Gene/L system log dataset**.

Each original BGL row begins with a ground-truth label:

| Dataset label | Meaning |
|---|---|
| `-` | Normal / non-alert |
| Any other value | Anomaly / alert |

The label is removed before the log is sent to the classification pipeline. It is used only after prediction to calculate evaluation metrics.

The binary task is:

```text
0 = normal
1 = anomaly
```

The LLM response must be exactly one of the following:

```json
{"label":"0"}
```

```json
{"label":"1"}
```

Malformed model responses are counted in the invalid-output metric.

---

## Proposed Method

```text
Raw BGL log
   ↓
Remove the ground-truth label
   ↓
Parse the message fields
   ↓
Normalize the message into a stable textual template
   ↓
Check the template classification cache
   ├── Cache hit → reuse the validated decision
   └── Cache miss
          ↓
      Apply BglTemplateGuard
          ├── High-confidence match → deterministic classification
          └── Ambiguous template → call the LLM
                                  ↓
                         Validate cache eligibility
                                  ↓
                   Store the current line-level result
```

### Why templates are used

BGL contains many repeated messages that differ only in runtime values such as:

- timestamps;
- node and component identifiers;
- file paths;
- hexadecimal addresses;
- register values;
- counters and numeric parameters.

For example:

```text
ciod: LOGIN chdir(/p/gb1/stella/RAPTOR/2183) failed: Input/output error
```

is normalized to:

```text
ciod: LOGIN chdir(<PATH>) failed: Input/output error
```

The stable textual template preserves the message meaning while allowing repeated instances to reuse one classification.

---

## Main Components

### `BglTemplateExtractor`

Normalizes dynamic values while preserving anomaly-relevant semantic terms such as:

```text
corrected
uncorrected
failed
terminated
Input/output error
```

### `BglTemplateClassificationCache`

Stores validated classifications by normalized template so repeated logs do not require another model call.

### `BglTemplateGuard`

Classifies only high-confidence BGL templates. It is intentionally conservative and avoids broad rules based only on words such as `error`, `failed`, `interrupt`, or `FATAL`.

### `BglTemplateValidationService`

Determines whether an LLM prediction is safe to cache. A suspicious result can still be used for the current row without being reused for every future occurrence of the same template.

### `PromptGenerator`

Contains the final BGL template-aware prompt. The latest reported experiment used:

```text
BGL_TEMPLATE_AWARE_FINAL_V17_MACHINE_CHECK_FIELDS
```

### `EvaluationMetricsService`

Calculates:

- Accuracy
- Precision
- Recall
- F1-score
- TP, TN, FP, FN
- Invalid output rate
- Average line response time
- Average direct-LLM response time
- Decision-source counts
- Template-cache size

---

# Latest Full-Run Results

The following values come from the latest completed experiment.

## Main Metrics

| Metric | Value |
|---|---:|
| Evaluated records | **2,914,482** |
| Accuracy | **0.9996** |
| Precision | **0.9948** |
| Recall | **0.9999** |
| F1-score | **0.9974** |
| Invalid output rate | **0.000000** |

More precise values calculated from the confusion matrix are:

| Metric | Precise value |
|---|---:|
| Accuracy | 0.9995797 |
| Precision | 0.9948129 |
| Recall | 0.9999310 |
| F1-score | 0.9973654 |

## Confusion Matrix

| Result | Count |
|---|---:|
| True Positive | **231,867** |
| True Negative | **2,681,390** |
| False Positive | **1,209** |
| False Negative | **16** |

The model missed 16 anomalous entries and produced 1,209 false alarms across more than 2.9 million evaluated records.

## Runtime and Cache Statistics

| Runtime metric | Value |
|---|---:|
| Average time per evaluated line | **1.68 ms** |
| Average time for records that directly called the LLM | **1,098.53 ms** |
| Total cache hits | **2,908,974** |
| Cache-hit rate | **99.81%** |
| Direct LLM calls | **4,466** |
| Direct LLM rate | **0.15%** |
| Direct Guard decisions | **1,042** |
| Direct Guard rate | **0.04%** |

## Decision Sources

| Decision source | Count | Share |
|---|---:|---:|
| Direct LLM | 4,466 | 0.153% |
| Cache from LLM | 2,385,894 | 81.864% |
| Direct Guard | 1,042 | 0.036% |
| Cache from Guard | 523,080 | 17.947% |
| **Total** | **2,914,482** | **100%** |

## Template Cache

| Cache source | Unique templates |
|---|---:|
| LLM-created templates | **4,382** |
| Guard-created templates | **1,042** |
| **Total unique cacheable templates** | **5,424** |

---

## Result Charts

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

# Comparison with Published Methods

The table below compares the latest proposed-method result with values reported in selected papers that evaluate BGL anomaly detection.

> **Important:** this is a reported-results comparison, not a controlled apples-to-apples benchmark. The papers use different models, dataset subsets, training regimes, sequence definitions, window sizes, and evaluation levels. The values must not be interpreted as a direct percentage improvement without reproducing all methods under one shared protocol.

| Method | Year | Main approach | Training on BGL | Evaluation unit | Precision | Recall | F1 |
|---|---:|---|---|---|---:|---:|---:|
| Exploring ChatGPT for Log-Based Anomaly Detection | 2023 | GPT-3.5, few-shot prompting, content sequence | No parameter training | Sequence, best reported window = 40; 2,000-log test subset | 0.455 | 1.000 | 0.625 |
| LogPrompt | 2023/2024 | GPT-3.5, zero-shot explicit CoT | No in-domain training | Session, fixed window = 100 | 0.249 | 0.834 | 0.384 |
| LogGPT: Log Anomaly Detection via GPT | 2023 | GPT-2 sequence model + PPO + Top-K reward | 5,000 normal sequences | One-minute log sequence | 0.940 | 0.977 | 0.958 |
| LogLLM | 2024/2025 | BERT embedder + projector + Llama 3 8B, supervised QLoRA fine-tuning | Yes, labeled 80% training split | Sequence, 100 messages, step = 100 | 0.861 | 0.979 | 0.916 |
| **LLMLogAnalyzer — proposed method** | Current | Template-aware prompt + cache + guard + validation | **No model fine-tuning** | **Individual log entry, 2,914,482 records** | **0.9948** | **0.9999** | **0.9974** |

## Interpretation of the Comparison

### Compared with prompt-only approaches

The proposed method reports higher line-level Precision and F1 than the prompt-only ChatGPT and LogPrompt results. The main architectural difference is that LLMLogAnalyzer does not rely on a generic prompt alone. It combines:

- BGL-aware textual templates;
- a conservative deterministic guard;
- validation before cache insertion;
- reuse of previously validated template decisions.

### Compared with LogGPT

LogGPT is a trained sequential model. It predicts the next log key and uses a Top-K rule with reinforcement-learning fine-tuning. It captures temporal sequence anomalies, whereas the current proposed method primarily performs semantic line/template classification.

### Compared with LogLLM

LogLLM is a supervised sequence classifier trained with labeled normal and anomalous samples. It uses BERT and Llama with a three-stage fine-tuning procedure. The proposed method does not fine-tune the language model and evaluates each log entry before template-level reuse.

### Main practical distinction

The proposed method made only **4,466 direct LLM calls** for **2,914,482 evaluated entries**. Most classifications were produced through validated template reuse, resulting in a **99.81% cache-hit rate**.

---

## Comparison Categories

| Category | Representative methods | Main characteristic |
|---|---|---|
| Prompt engineering without fine-tuning | Exploring ChatGPT, LogPrompt | Uses pretrained model knowledge directly |
| Trained sequential GPT model | LogGPT | Learns normal log-key ordering and temporal patterns |
| Supervised LLM fine-tuning | LogLLM | Learns dataset-specific normal and anomalous sequences |
| Hybrid template-aware inference | LLMLogAnalyzer | Combines semantic prompting, deterministic rules, validation, and caching |

---

## Fair-Comparison Requirements

A direct experimental comparison should use the same:

- BGL file and preprocessing;
- chronological train/development/test boundaries;
- log-message subset;
- sequence/window construction;
- classification unit;
- ground-truth aggregation rule;
- metric implementation;
- model-access policy.

For closer comparison with LogPrompt and LogLLM, the line-level predictions of this project can additionally be aggregated into non-overlapping windows of 100 messages.

For closer comparison with LogGPT, predictions can be aggregated into one-minute time windows.

Until those evaluations are performed, the literature table should be described as a comparison of **reported results under different protocols**.

---

## Error Analysis

BGL contains many normal entries with severe-looking words, including:

```text
FATAL
Error
failed
exception
interrupt
parity
uncorrectable
```

A classifier that treats these words as direct anomaly indicators can generate many false positives.

The proposed method instead prioritizes full template meaning. Examples include distinguishing:

```text
corrected parity counter / diagnostic event
```

from:

```text
unrecovered system failure / terminated execution
```

The opposite problem also occurs. Some file- or path-related messages look operationally ordinary but are anomalous in BGL, such as:

```text
ciod: LOGIN chdir(...) failed: Input/output error
```

The guard and validation layer explicitly address these high-risk template families.

---

## Technologies

- Java 17
- Spring Boot
- Maven
- MongoDB
- Ollama
- Qwen2.5 7B Instruct
- Spring WebFlux
- Spring Data MongoDB
- JFreeChart

---

## Project Structure

```text
src/main/java/masoud/dabbaghi/llmloganalyzer
│
├── config
├── controller
├── dto
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

# Running the Project

## 1. Clone

```bash
git clone https://github.com/masoudd2159/LLMLogAnalyzer.git
cd LLMLogAnalyzer
```

## 2. Start Ollama

```bash
ollama pull qwen2.5:7b-instruct
ollama serve
```

## 3. Configure `application.properties`

```properties
spring.application.name=LLMLogAnalyzer
server.port=8081

spring.data.mongodb.host=127.0.0.1
spring.data.mongodb.port=27017
spring.data.mongodb.database=LLMLogAnalyzer

model.api.ollama.url=http://localhost:11434/api/chat
model.api.ollama.model-name=qwen2.5:7b-instruct

bgl.location=D:/Programming/Thesis/Dataset/BGL/BGL.log

bgl.classification.template-cache.enabled=true
bgl.classification.template-guard.enabled=true
bgl.classification.cache-only-validated-llm-results=true
bgl.classification.template-key.include-metadata=false

charts.data.scope=current
charts.output-dir=.
```

## 4. Start MongoDB

```bash
mongod
```

## 5. Clear previous evaluation records before a clean run

```javascript
db.log_evaluations.deleteMany({})
```

Restart the application before a complete run so the in-memory cache begins empty.

## 6. Run

```bash
mvn spring-boot:run
```

---

## Reproducibility Notes

- The original BGL label must never be included in the model input.
- Ground truth is used only after prediction.
- Prompt, model, guard, cache, and validation settings should be frozen before the final test run.
- Each experiment should start with an empty evaluation collection and empty in-memory cache.
- Results should clearly state whether they are line-level, template-level, session-level, or time-window-level.
- The final thesis should include ablation experiments separating the effects of the prompt, template normalization, cache, validation, and guard.
- Reported literature results should not be presented as a controlled improvement unless the original protocols are reproduced.

---

## Selected Related Work

1. **Exploring ChatGPT for Log-Based Anomaly Detection** — zero-shot and few-shot ChatGPT evaluation on BGL and Spirit.
2. **Interpretable Online Log Analysis Using Large Language Models with Prompt Strategies (LogPrompt)** — zero-shot prompt strategies and interpretable anomaly detection.
3. **LogGPT: Log Anomaly Detection via GPT** — GPT-2 sequence modeling with reinforcement learning and Top-K anomaly detection.
4. **LogLLM: Log-based Anomaly Detection Using Large Language Models** — supervised BERT and Llama sequence classification.

The paper PDFs used in the thesis review are available in the repository's `thesis` directory.

---

## Author

**Masoud Dabbaghi**

Master's Thesis Project  
**Improving Anomaly Detection in System Logs Based on Large Language Models Using Prompt Engineering**
