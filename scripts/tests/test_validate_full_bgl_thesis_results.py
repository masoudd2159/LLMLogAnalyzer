import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
VALIDATOR_PATH = REPOSITORY / "scripts" / "validate_full_bgl_thesis_results.py"
SPEC = importlib.util.spec_from_file_location("full_bgl_validator", VALIDATOR_PATH)
assert SPEC and SPEC.loader
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


class FullBglResultValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.batch_id = "11111111-2222-4333-8444-555555555555"
        self.hybrid_id = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        self.prompt_id = "ffffffff-1111-4222-8333-444444444444"
        self.batch = Path(self.temporary.name) / self.batch_id
        self.dataset_sha = "a" * 64
        self.model_digest = "sha256:" + "b" * 64
        self.git_commit = "c" * 40
        self._build_valid_batch()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _write_json(path: Path, value: dict) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

    @staticmethod
    def _rewrite_checksums(directory: Path) -> None:
        lines = []
        for path in sorted(directory.iterdir(), key=lambda item: item.name):
            if path.is_file() and path.name != "artifact_sha256.txt":
                lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  ./{path.name}\n")
        (directory / "artifact_sha256.txt").write_text("".join(lines), encoding="utf-8")

    def _run(self, run_id: str, database: str, order: int, mode: str, guard: bool) -> dict:
        value = {
            "experimentBatchId": self.batch_id,
            "runId": run_id,
            "databaseName": database,
            "methodOrder": order,
            "classificationMode": mode,
            "templateGuardEnabled": guard,
            "status": "COMPLETED",
            "evaluationScope": "FULL_DATASET",
            "officialThesisRun": True,
            "recordLimitExplicit": False,
            "maxRecords": 3,
            "fullDatasetLineCount": 3,
            "rawLineCount": 3,
            "parsedLineCount": 3,
            "parseErrorCount": 0,
            "evaluationCoveragePercentage": 100.0,
            "datasetPath": "/data/BGL/BGL.log",
            "datasetSha256": self.dataset_sha,
            "gitCommit": self.git_commit,
            "modelVersion": self.model_digest,
            "modelDigest": self.model_digest,
        }
        value.update(copy.deepcopy(VALIDATOR.EXPECTED_SETTINGS))
        return value

    def _method(self, name: str, run: dict) -> None:
        directory = self.batch / name
        directory.mkdir(parents=True)
        for filename in VALIDATOR.METHOD_FILES:
            if filename == "artifact_sha256.txt":
                continue
            path = directory / filename
            if filename.endswith(".json"):
                self._write_json(path, {})
            else:
                path.write_bytes(b"fixture\n")

        self._write_json(directory / "bgl_experiment_runs.json", run)
        self._write_json(directory / "metrics_summary.json", {
            "runId": run["runId"], "total": 3, "consistencyChecks": {"fixture": True},
        })
        self._write_json(directory / "evaluation_scope_summary.json", {
            "datasetPath": run["datasetPath"],
            "datasetSha256": self.dataset_sha,
            "fullBglLineCount": 3,
            "evaluationScope": "FULL_DATASET",
            "officialThesisRun": True,
            "requestedRecordLimit": None,
            "processedRawRecords": 3,
            "parsedRecords": 3,
            "parseErrors": 0,
            "evaluationCoveragePercentage": 100.0,
            "firstRecordIndex": 1,
            "lastRecordIndex": 3,
        })
        self._rewrite_checksums(directory)

    def _build_valid_batch(self) -> None:
        self.batch.mkdir(parents=True)
        self._write_json(self.batch / "bgl_preprocessing_report.json", {
            "datasetPath": "/data/BGL/BGL.log", "sha256": self.dataset_sha,
            "rawLines": 3, "parsedLines": 3, "parseErrors": 0,
        })
        self._write_json(self.batch / "bgl_evaluation_plan.json", {
            "evaluationScope": "FULL_DATASET", "officialThesisRun": True,
            "callerRequestedRecordLimit": None, "fullDatasetLineCount": 3,
            "recordsToEvaluate": 3,
        })
        frozen = copy.deepcopy(VALIDATOR.EXPECTED_SETTINGS)
        frozen["thinking"] = frozen.pop("thinkingEnabled")
        frozen.update({
            "recordLimitExplicit": False, "evaluationScope": "FULL_DATASET",
            "maxRecords": 3, "datasetPath": "/data/BGL/BGL.log",
        })
        self._write_json(self.batch / "experiment_manifest.json", {
            "experimentBatchId": self.batch_id,
            "overallStatus": "COMPLETED",
            "evaluationScope": "FULL_DATASET",
            "officialThesisRun": True,
            "fullDatasetLineCount": 3,
            "hybridEvaluatedRecords": 3,
            "promptOnlyEvaluatedRecords": 3,
            "evaluationCoveragePercentage": 100.0,
            "datasetSha256": self.dataset_sha,
            "gitCommit": self.git_commit,
            "modelDigest": self.model_digest,
            "hybridRunId": self.hybrid_id,
            "promptOnlyRunId": self.prompt_id,
            "hybridDatabase": "hybrid",
            "promptOnlyDatabase": "prompt_only",
            "overallStartedAt": "2026-01-01T00:00:00Z",
            "frozenSettings": frozen,
        })
        hybrid = self._run(self.hybrid_id, "hybrid", 1, "HYBRID_GUARD_AND_LLM", True)
        prompt = self._run(self.prompt_id, "prompt_only", 2, "PROMPT_ONLY_LLM", False)
        self._method("hybrid", hybrid)
        self._method("prompt_only", prompt)

        comparison = self.batch / "comparison"
        comparison.mkdir()
        for filename in VALIDATOR.COMPARISON_FILES:
            if filename == "artifact_sha256.txt":
                continue
            path = comparison / filename
            if filename.endswith(".json"):
                self._write_json(path, {})
            elif filename == "comparison_table.csv":
                path.write_text(
                    "metric,hybrid,promptOnly,absoluteDelta,relativeDifference,percentagePointDelta\n",
                    encoding="utf-8",
                )
            else:
                path.write_bytes(b"fixture\n")
        self._write_json(comparison / "comparison_summary.json", {
            "experimentBatchId": self.batch_id,
            "hybridRunId": self.hybrid_id,
            "promptOnlyRunId": self.prompt_id,
            "evaluationScope": "FULL_DATASET",
            "officialThesisRun": True,
            "fullDatasetLineCount": 3,
            "hybridEvaluatedRecords": 3,
            "promptOnlyEvaluatedRecords": 3,
            "evaluationCoveragePercentage": 100.0,
        })
        self._rewrite_checksums(comparison)

    def _validate(self, expected_batch_id: str | None = None) -> dict:
        return VALIDATOR.validate(
            self.batch,
            self.git_commit,
            self.model_digest,
            expected_batch_id or self.batch_id,
            "2025-12-31T23:59:59Z",
        )

    def test_accepts_complete_exact_current_batch(self) -> None:
        summary = self._validate()
        self.assertEqual(self.batch_id, summary["batchId"])
        self.assertEqual(3, summary["datasetRecords"])

    def test_rejects_limited_method_scope(self) -> None:
        scope_path = self.batch / "prompt_only" / "evaluation_scope_summary.json"
        scope = json.loads(scope_path.read_text(encoding="utf-8"))
        scope["evaluationScope"] = "LIMITED_FIRST_N"
        self._write_json(scope_path, scope)
        self._rewrite_checksums(scope_path.parent)
        with self.assertRaisesRegex(ValueError, "Prompt-only scope artifact is not FULL_DATASET"):
            self._validate()

    def test_rejects_unchecksummed_artifact_change(self) -> None:
        (self.batch / "hybrid" / "run.log").write_text("tampered\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            self._validate()

    def test_rejects_wrong_invocation_batch(self) -> None:
        with self.assertRaisesRegex(ValueError, "not the batch reported"):
            self._validate("99999999-2222-4333-8444-555555555555")

    def test_rejects_noncompleted_manifest(self) -> None:
        path = self.batch / "experiment_manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["overallStatus"] = "FAILED"
        self._write_json(path, manifest)
        with self.assertRaisesRegex(ValueError, "overallStatus is not COMPLETED"):
            self._validate()

    def test_rejects_different_processed_record_count(self) -> None:
        path = self.batch / "hybrid" / "bgl_experiment_runs.json"
        run = json.loads(path.read_text(encoding="utf-8"))
        run["parsedLineCount"] = 2
        self._write_json(path, run)
        self._rewrite_checksums(path.parent)
        with self.assertRaisesRegex(ValueError, "did not evaluate every parsed line"):
            self._validate()


if __name__ == "__main__":
    unittest.main()
