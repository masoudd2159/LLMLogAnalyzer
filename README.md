# LLMLogAnalyzer

**Improving Anomaly Detection in System Logs Based on Large Language Models Using Template-Aware Prompt Engineering**

LLMLogAnalyzer is a Java Spring Boot research project developed for a Master's thesis.  
The project investigates whether a local open-source Large Language Model can classify BGL system logs as normal or
anomalous using a final template-aware method.

The current thesis version uses:

```text
Template Guard + Final Template-Aware LLM Prompt
```

This means the project does **not** compare multiple internal prompts anymore.  
Older Zero-Shot, Rule-Based, and Template-Aware prompt comparisons were used only during preliminary prompt design.

---

## Research Objective

The objective of this project is to evaluate an LLM-based anomaly detection method for BGL logs and compare the proposed
method with a selected baseline paper.

The proposed method focuses on:

- binary classification of BGL log entries;
- BGL alert / non-alert behavior;
- template-aware prompt engineering;
- deterministic template handling for frequent BGL patterns;
- local open-source LLM execution through Ollama;
- strict JSON model output;
- standard classification metrics.

The main comparison target is a baseline paper from the literature, not other internal prompt variants.

---

## Baseline Paper

The recommended baseline paper is:

```text
Exploring ChatGPT for Log-Based Anomaly Detection
```

This paper is suitable as a baseline because it also studies LLM-based log anomaly detection and includes BGL
experiments.

The final thesis comparison should be reported like this:

| Method          | Dataset      | Model                                               |             Accuracy |            Precision |               Recall |                   F1 |
|-----------------|--------------|-----------------------------------------------------|---------------------:|---------------------:|---------------------:|---------------------:|
| Baseline paper  | BGL          | Reported in paper                                   |      Fill from paper |      Fill from paper |      Fill from paper |      Fill from paper |
| Proposed method | BGL / BGL_2k | Qwen2.5 7B Instruct + Template Guard + Final Prompt | Fill from experiment | Fill from experiment | Fill from experiment | Fill from experiment |

---

## Dataset

This project uses the **BGL Blue Gene/L** log dataset.

Each BGL log entry starts with an original dataset label:

| Dataset Label   | Meaning            |
|-----------------|--------------------|
| `-`             | normal / non-alert |
| any other value | anomaly / alert    |

A critical thesis point is that the original dataset label is **not provided to the model** during inference.

The label is removed from the model input and is used only after prediction for evaluation.

The model receives only structured fields such as:

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

The original BGL label is intentionally excluded from the model input.

---

## Classification Task

Each BGL log entry is classified as:

```text
0 = normal / non-alert
1 = anomaly / alert
```

The expected model output is strictly limited to one JSON object:

```json
{
  "label": "0"
}
```

or:

```json
{
  "label": "1"
}
```

Invalid or malformed outputs are counted separately using the invalid output rate.

---

## Proposed Method

The final proposed method has two stages.

### Stage 1: Template Guard

A deterministic BGL template guard checks frequent known templates before calling the LLM.

The template guard is used to reduce false positives caused by severe-looking but normal BGL messages, such as:

- `ciod: Error loading ... invalid or missing program image`
- `ciod: LOGIN chdir(...) failed`
- `exception syndrome register`
- `program interrupt: privileged instruction`
- `program interrupt: trap instruction`
- `program interrupt: imprecise exception`
- `data address space`
- `store operation`
- `byte ordering exception`
- `rts internal error`
- `rts tree/torus link training failed`

The template guard checks known anomaly patterns first, then known normal patterns.  
This helps preserve recall while reducing false positives.

### Stage 2: Final Template-Aware Prompt

If no deterministic template matches, the log entry is sent to the LLM using a single final template-aware prompt.

The prompt includes:

- task definition;
- BGL alert / non-alert target definition;
- normal BGL templates;
- anomaly BGL templates;
- critical disambiguation rules;
- strict JSON output instruction.

---

## Model Used

The current experiment uses a local Ollama model:

```text
qwen2.5:7b-instruct
```

Example configuration:

```properties
model.api.ollama.url=http://localhost:11434/api/chat
model.api.ollama.model-name=qwen2.5:7b-instruct
```

The Ollama options are configured for deterministic output:

```java
temperature =0
top_p =0.1
repeat_penalty =1.0
seed =42
num_ctx =4096
num_predict =16
```

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

Reads BGL log entries, parses fields, removes the dataset label, applies the template guard, calls the LLM if needed,
and stores the evaluation result.

### BglTemplateGuard

A deterministic template-aware preprocessing class.  
It classifies frequent known BGL templates before the LLM call.

### PromptGenerator

Contains the single final template-aware prompt used in the thesis experiment.

### CallModelAi

Sends the final prompt and BGL log input to the local Ollama API and parses the model response.

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

Generates thesis-ready charts for the final proposed method.

---

## Technologies Used

- Java 17
- Spring Boot
- Maven
- MongoDB
- Ollama
- Qwen2.5 7B Instruct
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

### 2. Install and Run Ollama

Pull the model:

```bash
ollama pull qwen2.5:7b-instruct
```

Run Ollama:

```bash
ollama serve
```

### 3. Configure the Application

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

Update dataset paths according to your local machine.

### 4. Run MongoDB

Make sure MongoDB is running locally:

```bash
mongod
```

### 5. Clean Previous Experiment Results

Before running the final experiment, remove old prompt-comparison results from MongoDB:

```javascript
db.log_evaluations.deleteMany({})
```

### 6. Run the Project

```bash
mvn spring-boot:run
```

---

## Evaluation Metrics

| Metric        | Description                                                    |
|---------------|----------------------------------------------------------------|
| Accuracy      | Percentage of all correctly classified logs                    |
| Precision     | Percentage of predicted anomalies that were actually anomalies |
| Recall        | Percentage of real anomalies correctly detected                |
| F1-score      | Harmonic mean of precision and recall                          |
| TP            | Anomalies correctly detected as anomalies                      |
| TN            | Normal logs correctly detected as normal                       |
| FP            | Normal logs incorrectly classified as anomalies                |
| FN            | Anomalies incorrectly classified as normal                     |
| Invalid Rate  | Percentage of invalid model outputs                            |
| Response Time | Average inference time for one log entry                       |

---

## Latest Experimental Results

The latest experiment before adding the template guard was performed using:

```text
Model: qwen2.5:7b-instruct
Prompt: BGL_TEMPLATE_AWARE_FINAL_V4
Dataset: BGL_2k
```

### Main Metrics

| Metric                |        Value |
|-----------------------|-------------:|
| Accuracy              |        0.947 |
| Precision             |        0.569 |
| Recall                |        1.000 |
| F1-score              |        0.725 |
| Invalid Output Rate   |        0.000 |
| Average Response Time | ~1.1 seconds |

### Confusion Matrix

| Metric | Count |
|--------|------:|
| TP     |   140 |
| TN     |  1754 |
| FP     |   106 |
| FN     |     0 |

The result shows that the model detected all anomalous logs in the tested BGL_2k subset, producing perfect recall.  
However, precision is limited because 106 normal logs were incorrectly classified as anomalies.

---

## Result Charts

### Final Metrics

![Final Metrics](final_metrics.png)

### Final Confusion Matrix

![Final Confusion Matrix](final_confusion_matrix.png)

### Invalid Output Rate

![Final Invalid Output Rate](final_invalid_rate.png)

### Average Response Time

![Final Average Response Time](final_response_time.png)

---

## False Positive Analysis

The main limitation of the latest experiment is low precision.

The false positives mostly come from normal BGL logs that contain severe-looking words such as:

- `FATAL`
- `Error`
- `failed`
- `exception`
- `interrupt`

Frequent false-positive templates include:

| False-positive Template                                    | Expected Dataset Class |
|------------------------------------------------------------|------------------------|
| `ciod: Error loading ... invalid or missing program image` | normal / non-alert     |
| `ciod: LOGIN chdir(...) failed`                            | normal / non-alert     |
| `exception syndrome register`                              | normal / non-alert     |
| `program interrupt: privileged instruction`                | normal / non-alert     |
| `program interrupt: trap instruction`                      | normal / non-alert     |
| `program interrupt: imprecise exception`                   | normal / non-alert     |
| `data address space`                                       | normal / non-alert     |
| `store operation`                                          | normal / non-alert     |
| `byte ordering exception`                                  | normal / non-alert     |
| `rts internal error`                                       | normal / non-alert     |
| `rts tree/torus link training failed`                      | normal / non-alert     |
| `NFS Mount failed ... retrying`                            | normal / non-alert     |
| `ciod: pollControlDescriptors: Detected the debugger died` | normal / non-alert     |

These templates motivate the template guard.

---

## Precision Improvement Plan

Precision is calculated as:

```text
Precision = TP / (TP + FP)
```

In the latest test:

```text
Precision = 140 / (140 + 106) = 0.569
```

Therefore, precision can be improved by reducing false positives.

The current improvement plan is:

1. Add deterministic template rules for frequent known-normal BGL templates.
2. Keep known-anomaly rules before normal rules.
3. Call the LLM only when no deterministic template matches.
4. Store the decision source in MongoDB:
    - `TEMPLATE_GUARD`
    - `LLM`
5. Re-run the experiment after clearing old MongoDB results.
6. Report the updated result against the selected baseline paper.

Important thesis note:  
If template rules were designed using the current error file, the next final result should be evaluated on a separate
final test subset or on the full BGL dataset to avoid overfitting.

---

## Expected Impact of Template Guard

The current result has:

```text
TP = 140
FP = 106
Precision = 0.569
```

If frequent false-positive templates such as `ciod: Error loading ...` and `ciod: LOGIN chdir(...) failed` are handled
correctly, false positives should decrease significantly.

Example:

```text
If FP decreases from 106 to 30:

Precision = 140 / (140 + 30)
Precision = 0.823
```

The goal is to improve precision while preserving high recall.

---

## Thesis Notes

Important points for the thesis:

- The original BGL label is removed from the model input.
- Ground truth is used only after prediction for evaluation.
- The final experiment uses one final prompt.
- A deterministic template guard is used as a preprocessing step.
- Earlier prompt variants are preliminary experiments, not the main comparison target.
- The main comparison should be with the selected baseline paper.
- Current results show perfect recall but limited precision.
- The main research challenge is reducing false positives while preserving high recall.

---

## Author

**Masoud Dabbaghi**

Master's Degree Project  
**Improving Anomaly Detection in System Logs Based on Large Language Models Using Prompt Engineering**
