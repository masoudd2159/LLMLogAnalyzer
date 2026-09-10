#!/usr/bin/env python3
"""Validate one completed FULL_DATASET thesis batch without querying historical runs."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import sys
import uuid
from pathlib import Path
from typing import Any


METHOD_FILES = (
    "bgl_experiment_runs.json",
    "metrics_summary.json",
    "direct_metrics_summary.json",
    "decision_sources_summary.json",
    "template_summary.json",
    "latency_summary.json",
    "token_usage_summary.json",
    "confidence_summary.json",
    "error_summary.json",
    "evaluation_scope_summary.json",
    "template_analysis.csv",
    "validation_analysis.csv",
    "misclassified_records.jsonl.gz",
    "invalid_outputs.jsonl.gz",
    "run_environment.json",
    "run.log",
    "artifact_sha256.txt",
    "final_metrics.png",
    "final_confusion_matrix.png",
    "final_invalid_rate.png",
    "final_response_time.png",
    "final_decision_sources.png",
    "final_template_cache_size.png",
    "final_direct_metrics.png",
    "final_direct_confusion_matrix.png",
    "final_class_distribution.png",
    "final_error_breakdown.png",
    "final_llm_latency_percentiles.png",
    "final_top_error_templates.png",
)

COMPARISON_FILES = (
    "comparison_summary.json",
    "comparison_table.csv",
    "comparison_main_metrics.png",
    "comparison_direct_metrics.png",
    "comparison_llm_calls.png",
    "comparison_cache_rates.png",
    "comparison_runtime.png",
    "comparison_throughput.png",
    "artifact_sha256.txt",
)

EXPECTED_SETTINGS = {
    "modelName": "qwen3.5:35b",
    "temperature": 0.0,
    "topP": 0.9,
    "repeatPenalty": 1.0,
    "seed": 42,
    "format": "json",
    "thinkingEnabled": False,
    "numCtx": 8192,
    "numPredict": 160,
    "templateCacheEnabled": True,
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def load_json(path: Path) -> dict[str, Any]:
    require(path.is_file(), f"Required JSON artifact is missing: {path}")
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    require(isinstance(value, dict), f"Expected a JSON object: {path}")
    return value


def same_number(actual: Any, expected: float) -> bool:
    return not isinstance(actual, bool) and isinstance(actual, (int, float)) and math.isclose(
        float(actual), expected, rel_tol=0.0, abs_tol=1e-9
    )


def valid_uuid(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    try:
        uuid.UUID(value)
    except ValueError:
        return False
    return True


def valid_sha256(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdefABCDEF" for character in value)
    )


def validate_inventory(batch: Path) -> None:
    def required_file(path: Path, description: str) -> None:
        require(path.is_file(), f"Required {description} is missing: {path.name}")
        require(path.stat().st_size > 0, f"Required {description} is empty: {path.name}")

    for name in ("bgl_preprocessing_report.json", "bgl_evaluation_plan.json", "experiment_manifest.json"):
        required_file(batch / name, "root artifact")
    for method in ("hybrid", "prompt_only"):
        for name in METHOD_FILES:
            required_file(batch / method / name, f"{method} artifact")
    for name in COMPARISON_FILES:
        required_file(batch / "comparison" / name, "comparison artifact")


def validate_checksums(directory: Path) -> None:
    checksum_file = directory / "artifact_sha256.txt"
    require(checksum_file.is_file(), f"Checksum inventory is missing: {checksum_file}")
    checked_names: set[str] = set()
    for line in checksum_file.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split(maxsplit=1)
        require(len(parts) == 2 and len(parts[0]) == 64, f"Malformed checksum line in {checksum_file}")
        expected, name = parts
        name = name.lstrip(" *")
        target = (directory / name).resolve()
        require(target.parent == directory.resolve(), f"Checksum path escapes artifact directory: {name}")
        normalized_name = target.name
        require(name in {normalized_name, f"./{normalized_name}"}, f"Invalid checksum path: {name}")
        require(normalized_name not in checked_names, f"Duplicate checksum entry in {checksum_file}: {name}")
        require(target.is_file(), f"Checksummed artifact is missing: {target}")
        digest = hashlib.sha256()
        with target.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        require(digest.hexdigest() == expected.lower(), f"Checksum mismatch: {target}")
        checked_names.add(normalized_name)
    require(checked_names, f"Checksum inventory is empty: {checksum_file}")
    actual_names = {
        path.name for path in directory.iterdir()
        if path.is_file() and path.name != checksum_file.name
    }
    require(
        checked_names == actual_names,
        f"Checksum inventory does not exactly cover files in {directory}: "
        f"missing={sorted(actual_names - checked_names)}, extra={sorted(checked_names - actual_names)}",
    )


def validate_run(
    label: str,
    run: dict[str, Any],
    scope: dict[str, Any],
    preflight: dict[str, Any],
    expected_database: str,
    expected_run_id: str,
    expected_method_order: int,
    expected_classification_mode: str,
    expected_guard_enabled: bool,
) -> None:
    raw_lines = preflight["rawLines"]
    require(valid_uuid(expected_run_id), f"{label} manifest runId is missing or invalid")
    require(run.get("runId") == expected_run_id, f"{label} runId differs from manifest")
    require(run.get("databaseName") == expected_database, f"{label} database is not {expected_database}")
    require(run.get("methodOrder") == expected_method_order, f"{label} method order is incorrect")
    require(run.get("classificationMode") == expected_classification_mode, f"{label} classification mode is incorrect")
    require(run.get("templateGuardEnabled") is expected_guard_enabled, f"{label} Rule Guard flag is incorrect")
    require(run.get("status") == "COMPLETED", f"{label} status is not COMPLETED")
    require(run.get("evaluationScope") == "FULL_DATASET", f"{label} is not FULL_DATASET")
    require(run.get("officialThesisRun") is True, f"{label} is not marked as an official thesis run")
    require(run.get("recordLimitExplicit") is False, f"{label} contains an explicit record limit")
    require(run.get("maxRecords") == raw_lines, f"{label} maxRecords differs from the full dataset size")
    require(run.get("parseErrorCount") == 0, f"{label} has parser errors")
    require(run.get("fullDatasetLineCount") == raw_lines, f"{label} full dataset count differs from preflight")
    require(run.get("rawLineCount") == raw_lines, f"{label} did not process every raw line")
    require(run.get("parsedLineCount") == raw_lines, f"{label} did not evaluate every parsed line")
    require(same_number(run.get("evaluationCoveragePercentage"), 100.0), f"{label} coverage is not 100%")
    require(run.get("datasetSha256") == preflight["sha256"], f"{label} dataset SHA-256 differs from preflight")
    require(run.get("datasetPath") == preflight.get("datasetPath"), f"{label} dataset path differs from preflight")
    require(isinstance(run.get("gitCommit"), str) and run["gitCommit"], f"{label} Git commit is missing")
    require(isinstance(run.get("modelVersion"), str) and run["modelVersion"], f"{label} model version is missing")
    require(isinstance(run.get("modelDigest"), str) and run["modelDigest"], f"{label} model digest is missing")

    for field, expected in EXPECTED_SETTINGS.items():
        actual = run.get(field)
        if isinstance(expected, float):
            require(same_number(actual, expected), f"{label} frozen setting {field} differs: {actual}")
        else:
            require(actual == expected, f"{label} frozen setting {field} differs: {actual}")

    require(scope.get("evaluationScope") == "FULL_DATASET", f"{label} scope artifact is not FULL_DATASET")
    require(scope.get("officialThesisRun") is True, f"{label} scope artifact is not official")
    require(scope.get("requestedRecordLimit") is None, f"{label} scope artifact contains an explicit limit")
    require(scope.get("datasetPath") == run.get("datasetPath"), f"{label} scope dataset path differs")
    require(scope.get("datasetSha256") == run.get("datasetSha256"), f"{label} scope dataset SHA-256 differs")
    require(scope.get("fullBglLineCount") == raw_lines, f"{label} scope full line count differs")
    require(scope.get("processedRawRecords") == raw_lines, f"{label} scope raw count differs")
    require(scope.get("parsedRecords") == raw_lines, f"{label} scope parsed count differs")
    require(scope.get("parseErrors") == 0, f"{label} scope reports parser errors")
    require(same_number(scope.get("evaluationCoveragePercentage"), 100.0), f"{label} scope coverage is not 100%")
    require(scope.get("firstRecordIndex") == 1, f"{label} first record index is not 1")
    require(scope.get("lastRecordIndex") == raw_lines, f"{label} last record index is incomplete")


def validate(
    batch: Path,
    expected_git_commit: str | None,
    expected_model_digest: str | None,
    expected_batch_id: str | None,
    not_before: str | None,
) -> dict[str, Any]:
    batch = batch.resolve()
    require(batch.is_dir(), f"Batch directory does not exist: {batch}")
    validate_inventory(batch)

    manifest = load_json(batch / "experiment_manifest.json")
    preflight = load_json(batch / "bgl_preprocessing_report.json")
    plan = load_json(batch / "bgl_evaluation_plan.json")
    hybrid = load_json(batch / "hybrid" / "bgl_experiment_runs.json")
    prompt = load_json(batch / "prompt_only" / "bgl_experiment_runs.json")
    hybrid_scope = load_json(batch / "hybrid" / "evaluation_scope_summary.json")
    prompt_scope = load_json(batch / "prompt_only" / "evaluation_scope_summary.json")
    hybrid_metrics = load_json(batch / "hybrid" / "metrics_summary.json")
    prompt_metrics = load_json(batch / "prompt_only" / "metrics_summary.json")
    comparison = load_json(batch / "comparison" / "comparison_summary.json")

    raw_lines = preflight.get("rawLines")
    require(isinstance(raw_lines, int) and raw_lines > 0, "Preflight rawLines must be positive")
    require(preflight.get("parsedLines") == raw_lines, "Preflight did not parse every raw line")
    require(preflight.get("parseErrors") == 0, "Preflight parser errors must be zero")
    require(valid_sha256(preflight.get("sha256")), "Preflight SHA-256 is missing or malformed")
    require(
        isinstance(preflight.get("datasetPath"), str) and preflight["datasetPath"],
        "Preflight datasetPath is missing",
    )

    require(plan.get("evaluationScope") == "FULL_DATASET", "Evaluation plan is not FULL_DATASET")
    require(plan.get("officialThesisRun") is True, "Evaluation plan is not official")
    require(plan.get("callerRequestedRecordLimit") is None, "Bootstrap run must not contain BGL_MAX_RECORDS")
    require(plan.get("fullDatasetLineCount") == raw_lines, "Plan full dataset count differs from preflight")
    require(plan.get("recordsToEvaluate") == raw_lines, "Plan does not evaluate every preflight line")

    require(manifest.get("overallStatus") == "COMPLETED", "Manifest overallStatus is not COMPLETED")
    require(manifest.get("evaluationScope") == "FULL_DATASET", "Manifest is not FULL_DATASET")
    require(manifest.get("officialThesisRun") is True, "Manifest is not marked official")
    require(manifest.get("fullDatasetLineCount") == raw_lines, "Manifest full dataset count differs")
    require(manifest.get("hybridEvaluatedRecords") == raw_lines, "Manifest Hybrid count differs")
    require(manifest.get("promptOnlyEvaluatedRecords") == raw_lines, "Manifest Prompt-only count differs")
    require(same_number(manifest.get("evaluationCoveragePercentage"), 100.0), "Manifest coverage is not 100%")
    require(manifest.get("datasetSha256") == preflight["sha256"], "Manifest dataset SHA-256 differs")
    require(manifest.get("experimentBatchId") == batch.name, "Manifest batch ID differs from its directory")
    require(valid_uuid(manifest.get("experimentBatchId")), "Manifest experimentBatchId is not a UUID")
    require(manifest.get("hybridDatabase") == "hybrid", "Manifest Hybrid database is incorrect")
    require(manifest.get("promptOnlyDatabase") == "prompt_only", "Manifest Prompt-only database is incorrect")

    frozen = manifest.get("frozenSettings")
    require(isinstance(frozen, dict), "Manifest frozenSettings are missing")
    for field, expected in EXPECTED_SETTINGS.items():
        manifest_field = "thinking" if field == "thinkingEnabled" else field
        actual = frozen.get(manifest_field)
        if isinstance(expected, float):
            require(same_number(actual, expected), f"Manifest frozen setting {manifest_field} differs: {actual}")
        else:
            require(actual == expected, f"Manifest frozen setting {manifest_field} differs: {actual}")
    require(frozen.get("recordLimitExplicit") is False, "Manifest frozen settings contain an explicit record limit")
    require(frozen.get("evaluationScope") == "FULL_DATASET", "Manifest frozen scope is not FULL_DATASET")
    require(frozen.get("maxRecords") == raw_lines, "Manifest frozen maxRecords differs from preflight")
    require(frozen.get("datasetPath") == preflight["datasetPath"], "Manifest frozen dataset path differs")

    if expected_batch_id:
        require(batch.name == expected_batch_id, "Validated directory is not the batch reported by this invocation")
    if not_before:
        threshold = dt.datetime.fromisoformat(not_before.replace("Z", "+00:00"))
        started = dt.datetime.fromisoformat(str(manifest.get("overallStartedAt", "")).replace("Z", "+00:00"))
        require(threshold.tzinfo is not None and started.tzinfo is not None, "Batch timestamps must include a timezone")
        require(started >= threshold, "Manifest start time predates this bootstrap invocation")

    validate_run(
        "Hybrid", hybrid, hybrid_scope, preflight, "hybrid", manifest.get("hybridRunId"),
        1, "HYBRID_GUARD_AND_LLM", True,
    )
    validate_run(
        "Prompt-only", prompt, prompt_scope, preflight, "prompt_only", manifest.get("promptOnlyRunId"),
        2, "PROMPT_ONLY_LLM", False,
    )
    require(hybrid.get("experimentBatchId") == manifest.get("experimentBatchId"), "Hybrid batch ID differs")
    require(prompt.get("experimentBatchId") == manifest.get("experimentBatchId"), "Prompt-only batch ID differs")

    for label, metrics, run in (("Hybrid", hybrid_metrics, hybrid), ("Prompt-only", prompt_metrics, prompt)):
        require(metrics.get("runId") == run.get("runId"), f"{label} metrics runId differs")
        require(metrics.get("total") == raw_lines, f"{label} metrics total differs from evaluated records")
        checks = metrics.get("consistencyChecks")
        require(isinstance(checks, dict) and checks, f"{label} consistency checks are missing")
        require(all(value is True for value in checks.values()), f"{label} contains a failed consistency check")

    require(comparison.get("experimentBatchId") == manifest.get("experimentBatchId"), "Comparison batch ID differs")
    require(comparison.get("hybridRunId") == hybrid.get("runId"), "Comparison Hybrid runId differs")
    require(comparison.get("promptOnlyRunId") == prompt.get("runId"), "Comparison Prompt-only runId differs")
    require(comparison.get("evaluationScope") == "FULL_DATASET", "Comparison is not FULL_DATASET")
    require(comparison.get("officialThesisRun") is True, "Comparison is not marked official")
    require(comparison.get("fullDatasetLineCount") == raw_lines, "Comparison full dataset count differs")
    require(comparison.get("hybridEvaluatedRecords") == raw_lines, "Comparison Hybrid count differs")
    require(comparison.get("promptOnlyEvaluatedRecords") == raw_lines, "Comparison Prompt-only count differs")
    require(same_number(comparison.get("evaluationCoveragePercentage"), 100.0), "Comparison coverage is not 100%")
    comparison_csv = batch / "comparison" / "comparison_table.csv"
    require(comparison_csv.stat().st_size > 0, "Comparison CSV is empty")
    comparison_lines = comparison_csv.read_text(encoding="utf-8").splitlines()
    header = comparison_lines[0].strip() if comparison_lines else ""
    require(
        header == "metric,hybrid,promptOnly,absoluteDelta,relativeDifference,percentagePointDelta",
        "Comparison CSV header is invalid",
    )

    validate_checksums(batch / "hybrid")
    validate_checksums(batch / "prompt_only")
    validate_checksums(batch / "comparison")

    require(hybrid.get("gitCommit") == prompt.get("gitCommit") == manifest.get("gitCommit"), "Git commits differ")
    require(hybrid.get("modelDigest") == prompt.get("modelDigest") == manifest.get("modelDigest"), "Model digests differ")
    require(hybrid.get("modelVersion") == prompt.get("modelVersion"), "Resolved model versions differ")
    require(hybrid.get("datasetSha256") == prompt.get("datasetSha256"), "Run dataset hashes differ")
    for field in EXPECTED_SETTINGS:
        require(hybrid.get(field) == prompt.get(field), f"Frozen setting differs between methods: {field}")

    if expected_git_commit:
        require(manifest.get("gitCommit") == expected_git_commit, "Manifest Git commit differs from bootstrap checkout")
    if expected_model_digest:
        require(manifest.get("modelDigest") == expected_model_digest, "Manifest model digest differs from installed Ollama model")

    return {
        "batchId": manifest.get("experimentBatchId"),
        "datasetSha256": preflight["sha256"],
        "datasetRecords": raw_lines,
        "evaluationScope": "FULL_DATASET",
        "evaluationCoveragePercentage": 100.0,
        "hybridRunId": hybrid["runId"],
        "promptOnlyRunId": prompt["runId"],
        "gitCommit": manifest["gitCommit"],
        "modelDigest": manifest["modelDigest"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("batch_directory", type=Path)
    parser.add_argument("--expected-git-commit")
    parser.add_argument("--expected-model-digest")
    parser.add_argument("--expected-batch-id")
    parser.add_argument("--not-before", help="UTC ISO-8601 lower bound for manifest overallStartedAt")
    args = parser.parse_args()
    try:
        summary = validate(
            args.batch_directory,
            args.expected_git_commit,
            args.expected_model_digest,
            args.expected_batch_id,
            args.not_before,
        )
    except (OSError, TypeError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"FULL_DATASET validation failed: {error}", file=sys.stderr)
        return 1
    json.dump(summary, sys.stdout, indent=2)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
