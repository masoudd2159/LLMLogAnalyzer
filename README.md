# LLMLogAnalyzer

**Improving Anomaly Detection in System Logs Based on Large Language Models Using Template-Aware Prompt Engineering**

LLMLogAnalyzer is a Java Spring Boot research project developed for a Master's thesis.  
The project investigates how Large Language Models can be used for binary anomaly detection in BGL system logs.

The current research focus is a **prompt-only template-aware method**.  
A deterministic `BglTemplateGuard` class exists in the codebase for future hybrid experiments, but it is intentionally not used in the current prompt-only evaluation.

---

## Research Objective

The objective of this project is to evaluate whether a Large Language Model can classify BGL log entries as normal or anomalous when it is guided by a carefully designed template-aware prompt.

The method focuses on:

- BGL alert / non-alert classification;
- prompt engineering for system log analysis;
- strict JSON output generation;
- local model execution through Ollama;
- evaluation using standard classification metrics;
- comparison with a selected baseline paper from the literature.

The final comparison target is a baseline paper, not internal prompt variants.

---

## Dataset

This project uses the **BGL Blue Gene/L** log dataset.

Each BGL log entry starts with an original dataset label:

| Dataset Label | Meaning |
|---|---|
| `-` | Normal / non-alert |
| any other value | Anomaly / alert |

A key point in this project is that the original dataset label is **not provided to the model** during inference.  
The label is removed before the log entry is sent to the LLM and is used only after prediction for evaluation.

The model receives structured fields such as:

```text
timestamp
date
location1
datetime
location2
category
component
severity
message
```

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

The current proposed method is:

```text
Final Template-Aware Prompt + Local LLM
```

The prompt provides the model with:

- the binary classification task;
- the BGL alert / non-alert target definition;
- known normal BGL templates;
- known anomaly BGL templates;
- critical disambiguation rules;
- a conservative fallback rule;
- strict JSON output constraints.

The prompt is designed to reduce false positives caused by severe-looking words such as:

- `FATAL`
- `ERROR`
- `failed`
- `exception`
- `interrupt`
- `ASSERT`

These words are treated as weak signals only.  
The message template has higher priority than severity.

---

## Template Guard Status

The repository may contain a `BglTemplateGuard` class.

In the current prompt-only experiment, this class is **not used**.  
It is kept in the project for possible future hybrid experiments, where known deterministic BGL templates may be handled before calling the LLM.

Current experiment flow:

```text
BGL log
→ remove original dataset label
→ send structured log fields to final template-aware prompt
→ receive JSON label from LLM
→ compare with ground truth
→ calculate metrics
```

---

## Model

The project is designed to work with local Ollama models.

Example configuration:

```properties
model.api.ollama.url=http://localhost:11434/api/chat
model.api.ollama.model-name=qwen2.5:7b-instruct
```

For stronger experiments, a larger instruction-tuned model can be tested while keeping the prompt and dataset fixed.

Recommended evaluation approach:

```text
Same dataset
Same prompt
Same settings
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
├── entity
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
│   ├── BglTemplateGuard.java
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

Reads BGL logs, parses each line, removes the original dataset label from the model input, calls the LLM using the final prompt, and stores the evaluation result.

### PromptGenerator

Contains the final template-aware prompt used for the current prompt-only experiment.

### CallModelAi

Sends the prompt and structured log entry to the local Ollama API and parses the returned JSON label.

### EvaluationMetricsService

Calculates the main evaluation metrics:

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

Generates charts for thesis reporting and result analysis.

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

Pull and run a local instruction-tuned model:

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

| Metric | Description |
|---|---|
| Accuracy | Overall classification correctness |
| Precision | Reliability of anomaly predictions |
| Recall | Ability to detect real anomalies |
| F1-score | Balance between precision and recall |
| TP | Anomalies correctly detected |
| TN | Normal logs correctly detected |
| FP | Normal logs incorrectly classified as anomalies |
| FN | Anomalies missed by the model |
| Invalid Rate | Invalid or malformed model outputs |
| Response Time | Average inference time per log entry |

---

## Result Charts

The following charts are generated after running the experiment.  
They are included to visualize model performance without hard-coding exact numbers in the README.

### Main Evaluation Metrics

![Final Metrics](final_metrics.png)

### Confusion Matrix

![Final Confusion Matrix](final_confusion_matrix.png)

### Invalid Output Rate

![Final Invalid Output Rate](final_invalid_rate.png)

### Average Response Time

![Final Average Response Time](final_response_time.png)

---

## Error Analysis

In BGL logs, many normal messages contain severe-looking words.  
For example, some normal / non-alert messages may include:

- `FATAL`
- `Error`
- `failed`
- `exception`
- `interrupt`

This can cause false positives if the model relies on keywords instead of message templates.

The final prompt therefore emphasizes that:

```text
Template meaning is more important than severity words.
```

---

## Baseline Comparison Plan

The final thesis should compare the proposed method with a selected baseline paper.

Recommended baseline:

```text
Exploring ChatGPT for Log-Based Anomaly Detection
```

The comparison can be reported as:

| Method | Dataset | Model | Accuracy | Precision | Recall | F1 |
|---|---|---|---:|---:|---:|---:|
| Baseline paper | BGL | Reported in paper | Fill from paper | Fill from paper | Fill from paper | Fill from paper |
| Proposed method | BGL / BGL subset | Local instruction-tuned LLM + final template-aware prompt | Fill from experiment | Fill from experiment | Fill from experiment | Fill from experiment |

---

## Thesis Notes

Important points:

- The original BGL label is removed from the model input.
- Ground truth is used only after prediction.
- The current experiment is prompt-only.
- `BglTemplateGuard` exists but is not used in the current run.
- The final comparison should be with a baseline paper.
- A stronger model can be tested while keeping prompt and dataset fixed.
- Final reported results should be evaluated on a held-out test set or a clearly defined dataset split.

---

## Author

**Masoud Dabbaghi**

Master's Degree Project  
**Improving Anomaly Detection in System Logs Based on Large Language Models Using Prompt Engineering**
