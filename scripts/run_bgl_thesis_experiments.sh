#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ -v BGL_MAX_RECORDS ]]; then
  BGL_LIMIT_EXPLICIT=true
  CALLER_BGL_MAX_RECORDS="$BGL_MAX_RECORDS"
else
  BGL_LIMIT_EXPLICIT=false
  CALLER_BGL_MAX_RECORDS=""
fi
export BGL_LIMIT_EXPLICIT

# Load .env while preserving variables explicitly supplied by the caller.
if [[ -f .env ]]; then
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    [[ "$key" == "BGL_MAX_RECORDS" ]] && continue
    if [[ -z "${!key+x}" ]]; then export "$key=$value"; fi
  done < <(sed -e 's/^[[:space:]]*//' -e '/^#/d' -e '/^[[:space:]]*$/d' .env)
fi

: "${MODEL_NAME:=qwen3.5:35b}"
: "${TEMPERATURE:=0}"
: "${TOP_P:=0.9}"
: "${REPEAT_PENALTY:=1.0}"
: "${SEED:=42}"
: "${FORMAT:=json}"
: "${THINKING:=false}"
: "${NUM_CTX:=8192}"
: "${NUM_PREDICT:=160}"
: "${BGL_TEMPLATE_CACHE:=true}"
: "${MONGODB_HYBRID_URI:=mongodb://127.0.0.1:27017/hybrid}"
: "${MONGODB_PROMPT_ONLY_URI:=mongodb://127.0.0.1:27017/prompt_only}"
: "${THESIS_RESULTS_DIR:=results/thesis}"

export MODEL_NAME TEMPERATURE TOP_P REPEAT_PENALTY SEED FORMAT THINKING NUM_CTX NUM_PREDICT
export BGL_TEMPLATE_CACHE

if [[ "$BGL_LIMIT_EXPLICIT" == true ]]; then
  [[ "$CALLER_BGL_MAX_RECORDS" =~ ^[1-9][0-9]*$ ]] \
    || { echo "BGL_MAX_RECORDS must be a positive integer" >&2; exit 2; }
  export BGL_MAX_RECORDS="$CALLER_BGL_MAX_RECORDS"
else
  unset BGL_MAX_RECORDS
fi

[[ "$MODEL_NAME" == "qwen3.5:35b" ]] || { echo "MODEL_NAME must be qwen3.5:35b" >&2; exit 2; }
[[ "$TEMPERATURE" == "0" ]] || { echo "TEMPERATURE must be 0" >&2; exit 2; }
[[ "$TOP_P" == "0.9" ]] || { echo "TOP_P must be 0.9" >&2; exit 2; }
[[ "$REPEAT_PENALTY" == "1.0" ]] || { echo "REPEAT_PENALTY must be 1.0" >&2; exit 2; }
[[ "$SEED" == "42" && "$FORMAT" == "json" && "$THINKING" == "false" ]] || { echo "Frozen seed/format/thinking settings differ" >&2; exit 2; }
[[ "$NUM_CTX" == "8192" && "$NUM_PREDICT" == "160" ]] || { echo "Frozen context settings differ" >&2; exit 2; }
[[ "$BGL_TEMPLATE_CACHE" == "true" ]] || { echo "BGL_TEMPLATE_CACHE must be true for both methods" >&2; exit 2; }

GIT_COMMIT="$(git rev-parse HEAD)"
export GIT_COMMIT
GIT_BRANCH="$(git branch --show-current)"
[[ "$GIT_BRANCH" == "Qwen3.5-35B" ]] || { echo "Expected branch Qwen3.5-35B, found $GIT_BRANCH" >&2; exit 2; }

if command -v uuidgen >/dev/null 2>&1; then
  EXPERIMENT_BATCH_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
elif [[ -r /proc/sys/kernel/random/uuid ]]; then
  EXPERIMENT_BATCH_ID="$(</proc/sys/kernel/random/uuid)"
else
  EXPERIMENT_BATCH_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
fi
export EXPERIMENT_BATCH_ID
THESIS_OVERALL_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export THESIS_OVERALL_STARTED_AT

RESULT_ROOT="$REPO_ROOT/$THESIS_RESULTS_DIR/$EXPERIMENT_BATCH_ID"
HYBRID_DIR="$RESULT_ROOT/hybrid"
PROMPT_DIR="$RESULT_ROOT/prompt_only"
COMPARISON_DIR="$RESULT_ROOT/comparison"
MANIFEST="$RESULT_ROOT/experiment_manifest.json"
mkdir -p "$(dirname "$RESULT_ROOT")"
if [[ -e "$RESULT_ROOT" ]]; then
  echo "Refusing to overwrite existing experiment directory: $RESULT_ROOT" >&2
  exit 3
fi
mkdir -p "$HYBRID_DIR" "$PROMPT_DIR" "$COMPARISON_DIR"

export THESIS_PREFLIGHT_REPORT="$RESULT_ROOT/bgl_preprocessing_report.json"
export THESIS_HYBRID_DIR="$HYBRID_DIR"
export THESIS_PROMPT_ONLY_DIR="$PROMPT_DIR"
export THESIS_COMPARISON_DIR="$COMPARISON_DIR"
export THESIS_MANIFEST_FILE="$MANIFEST"

write_failure_manifest() {
  local step="$1"
  python3 - "$MANIFEST" "$EXPERIMENT_BATCH_ID" "$step" "$GIT_COMMIT" "$HYBRID_DIR" "$PROMPT_DIR" "$COMPARISON_DIR" "$THESIS_OVERALL_STARTED_AT" <<'PY'
import datetime, json, pathlib, sys
path = pathlib.Path(sys.argv[1])
hybrid, prompt, comparison = map(pathlib.Path, sys.argv[5:8])
def run_id(directory):
    candidate = directory / ".run_id"
    return candidate.read_text(encoding="utf-8").strip() if candidate.exists() else None
data = {
    "experimentBatchId": sys.argv[2], "overallStatus": "FAILED", "failedStep": sys.argv[3],
    "gitCommit": sys.argv[4], "hybridRunId": run_id(hybrid), "promptOnlyRunId": run_id(prompt),
    "hybridDatabase": "hybrid", "promptOnlyDatabase": "prompt_only",
    "hybridArtifactDirectory": str(hybrid), "promptOnlyArtifactDirectory": str(prompt),
    "comparisonArtifactDirectory": str(comparison), "overallStartedAt": sys.argv[8],
    "overallFinishedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()
}
plan_path = path.parent / "bgl_evaluation_plan.json"
if plan_path.exists():
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    data.update({
        "evaluationScope": plan.get("evaluationScope"),
        "officialThesisRun": plan.get("officialThesisRun"),
        "fullDatasetLineCount": plan.get("fullDatasetLineCount")
    })
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY
}

spring_profile() {
  local profile="$1"
  ./mvnw -q spring-boot:run "-Dspring-boot.run.profiles=$profile" \
    '-Dspring-boot.run.arguments=--spring.main.web-application-type=none'
}

rewrite_checksums() {
  local directory="$1"
  (
    cd "$directory"
    find . -maxdepth 1 -type f ! -name artifact_sha256.txt -print0 \
      | sort -z | xargs -0 sha256sum > artifact_sha256.txt
  )
}

run_method() {
  local name="$1" database_name="$2" database_uri="$3" order="$4" guard="$5" directory="$6"
  local run_id_file="$directory/.run_id"
  echo "[$name] starting $BGL_EVALUATION_SCOPE inference"
  echo "records=$BGL_MAX_RECORDS database=$database_name"
  if ! (
    export MONGODB_URI="$database_uri" EXPERIMENT_DATABASE_NAME="$database_name" EXPERIMENT_METHOD_ORDER="$order"
    export BGL_TEMPLATE_GUARD="$guard" EXPERIMENT_RUN_ID_FILE="$run_id_file"
    spring_profile experiment
  ) 2>&1 | tee "$directory/run.log"; then
    if [[ -s "$run_id_file" ]]; then
      local failed_id
      failed_id="$(tr -d '\r\n' < "$run_id_file")"
      (
        export MONGODB_URI="$database_uri" RUN_ID="$failed_id" CHART_OUTPUT_DIR="$directory"
        spring_profile thesis-artifacts
      ) >>"$directory/run.log" 2>&1 || true
    fi
    write_failure_manifest "$name inference"
    return 1
  fi

  local run_id
  run_id="$(tr -d '\r\n' < "$run_id_file")"
  [[ -n "$run_id" ]] || { write_failure_manifest "$name runId capture"; return 1; }
  echo "[$name] inference completed; exporting runId=$run_id"
  if ! (
    export MONGODB_URI="$database_uri" RUN_ID="$run_id" CHART_SCOPE=run CHART_OUTPUT_DIR="$directory"
    export BGL_TEMPLATE_GUARD="$guard"
    spring_profile thesis-artifacts
  ) 2>&1 | tee -a "$directory/run.log"; then
    write_failure_manifest "$name artifact generation"
    return 1
  fi
  rewrite_checksums "$directory"
}

echo "Batch: $EXPERIMENT_BATCH_ID"
echo "Result directory: $RESULT_ROOT"
echo "Git commit: $GIT_COMMIT"
echo "[preflight] inspecting the full dataset"
if ! (export CHART_OUTPUT_DIR="$RESULT_ROOT"; spring_profile preprocess); then
  write_failure_manifest "dataset preflight"
  exit 1
fi

mapfile -t PLAN_VALUES < <(python3 - "$RESULT_ROOT/bgl_evaluation_plan.json" "$THESIS_PREFLIGHT_REPORT" <<'PY'
import json, sys
plan = json.load(open(sys.argv[1], encoding="utf-8"))
report = json.load(open(sys.argv[2], encoding="utf-8"))
for value in (
    plan["recordsToEvaluate"], plan["evaluationScope"], str(plan["officialThesisRun"]).lower(),
    plan["fullDatasetLineCount"], report["datasetPath"], report["sha256"],
    report["rawLines"], report["parsedLines"], report["parseErrors"]
):
    print(value)
PY
)
BGL_MAX_RECORDS="${PLAN_VALUES[0]}"
BGL_EVALUATION_SCOPE="${PLAN_VALUES[1]}"
BGL_OFFICIAL_THESIS_RUN="${PLAN_VALUES[2]}"
BGL_FULL_DATASET_LINE_COUNT="${PLAN_VALUES[3]}"
DATASET_PATH="${PLAN_VALUES[4]}"
PREFLIGHT_DATASET_SHA="${PLAN_VALUES[5]}"
PREFLIGHT_RAW_LINES="${PLAN_VALUES[6]}"
PREFLIGHT_PARSED_LINES="${PLAN_VALUES[7]}"
PREFLIGHT_PARSE_ERRORS="${PLAN_VALUES[8]}"
export BGL_MAX_RECORDS BGL_EVALUATION_SCOPE BGL_OFFICIAL_THESIS_RUN BGL_FULL_DATASET_LINE_COUNT

echo "[preflight] completed"
echo "rawLines=$PREFLIGHT_RAW_LINES"
echo "parsedLines=$PREFLIGHT_PARSED_LINES"
echo "parseErrors=$PREFLIGHT_PARSE_ERRORS"

if [[ "$BGL_EVALUATION_SCOPE" == "LIMITED_FIRST_N" ]]; then
  echo "============================================================"
  echo " LIMITED / SMOKE EVALUATION"
  echo " This is NOT the official full-dataset thesis run"
  echo " Requested records: $BGL_MAX_RECORDS"
  echo " Full dataset records: $BGL_FULL_DATASET_LINE_COUNT"
  echo "============================================================"
else
  echo "============================================================"
  echo " FINAL BGL FULL-DATASET THESIS EXPERIMENT"
  echo "============================================================"
  echo "Dataset: $DATASET_PATH"
  echo "Dataset SHA-256: $PREFLIGHT_DATASET_SHA"
  echo "Full records: $BGL_FULL_DATASET_LINE_COUNT"
  echo "Evaluation scope: $BGL_EVALUATION_SCOPE"
  echo "Model: $MODEL_NAME"
  echo "Methods:"
  echo "  1. Hybrid Rule Guard + LLM"
  echo "  2. Prompt-only LLM"
  echo "============================================================"
fi

run_method Hybrid hybrid "$MONGODB_HYBRID_URI" 1 true "$HYBRID_DIR" || exit $?
HYBRID_RUN_ID="$(tr -d '\r\n' < "$HYBRID_DIR/.run_id")"
run_method Prompt-only prompt_only "$MONGODB_PROMPT_ONLY_URI" 2 false "$PROMPT_DIR" || exit $?
PROMPT_ONLY_RUN_ID="$(tr -d '\r\n' < "$PROMPT_DIR/.run_id")"

echo "[comparison] validating frozen invariants and generating paired artifacts"
if ! (spring_profile thesis-comparison); then
  write_failure_manifest "comparison"
  exit 1
fi
rewrite_checksums "$COMPARISON_DIR"

DATASET_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["datasetSha256"])' "$HYBRID_DIR/bgl_experiment_runs.json")"
MODEL_DIGEST="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["modelDigest"])' "$HYBRID_DIR/bgl_experiment_runs.json")"
HYBRID_EVALUATED="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["parsedLineCount"])' "$HYBRID_DIR/bgl_experiment_runs.json")"
PROMPT_EVALUATED="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["parsedLineCount"])' "$PROMPT_DIR/bgl_experiment_runs.json")"

echo "============================================================"
if [[ "$BGL_EVALUATION_SCOPE" == "FULL_DATASET" ]]; then
  echo " FULL-DATASET THESIS EXPERIMENT COMPLETED"
else
  echo " LIMITED / SMOKE PAIRED EXPERIMENT COMPLETED"
fi
echo "============================================================"
echo "experimentBatchId: $EXPERIMENT_BATCH_ID"
echo "Evaluation scope: $BGL_EVALUATION_SCOPE"
echo "Dataset records: $BGL_FULL_DATASET_LINE_COUNT"
echo "Hybrid evaluated: $HYBRID_EVALUATED"
echo "Prompt-only evaluated: $PROMPT_EVALUATED"
echo "Coverage: $(python3 -c 'import sys; print(f"{float(sys.argv[1])*100/float(sys.argv[2]):.6g}%")' "$HYBRID_EVALUATED" "$BGL_FULL_DATASET_LINE_COUNT")"
echo "Hybrid runId: $HYBRID_RUN_ID"
echo "Prompt-only runId: $PROMPT_ONLY_RUN_ID"
echo "result directory: $RESULT_ROOT"
echo "databases: hybrid, prompt_only"
echo "Git commit: $GIT_COMMIT"
echo "dataset SHA-256: $DATASET_SHA"
echo "model digest: $MODEL_DIGEST"
echo "============================================================"
