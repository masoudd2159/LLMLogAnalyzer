package masoud.dabbaghi.llmloganalyzer.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapScriptStaticTest {

    private final Path repository = Path.of(System.getProperty("user.dir"));

    @Test
    void bootstrapPinsSupportedPlatformsOfficialInputsAndFullDatasetRunner() throws Exception {
        String script = readNormalized("scripts/bootstrap_and_run_full_bgl_thesis.sh");

        assertTrue(script.startsWith("#!/usr/bin/env bash\nset -Eeuo pipefail"));
        assertTrue(script.contains("22.04) MONGODB_UBUNTU_CODENAME=\"jammy\""));
        assertTrue(script.contains("24.04) MONGODB_UBUNTU_CODENAME=\"noble\""));
        assertTrue(script.contains("https://repo.mongodb.org/apt/ubuntu"));
        assertTrue(script.contains("mongodb-org/8.0"));
        assertTrue(script.contains("db.runCommand({ ping: 1 })"));
        assertTrue(script.contains("readonly REQUIRED_MODEL='qwen3.5:35b'"));
        assertTrue(script.contains("ollama pull \"$REQUIRED_MODEL\""));
        assertTrue(script.contains("https://zenodo.org/records/8196385/files/BGL.zip?download=1"));
        assertTrue(script.contains("4452953c470f2d95fcb32d5f6e733f7a"));
        assertTrue(script.contains("readonly EXPORT_FULL_LOG_EVALUATIONS_DEFAULT=false"));
        assertTrue(script.contains("unset BGL_MAX_RECORDS"));
        assertTrue(script.contains("./scripts/run_bgl_thesis_experiments.sh"));
        assertTrue(script.indexOf("detect_supported_os /etc/os-release")
                < script.indexOf("mkdir -p \"$REPO_ROOT/results/thesis\""));
        assertTrue(occurrences(script, "unset BGL_MAX_RECORDS") >= 2);
    }

    @Test
    void packagingIsBoundToTheSingleBatchCreatedByThisInvocation() throws Exception {
        String script = readNormalized("scripts/bootstrap_and_run_full_bgl_thesis.sh");

        assertTrue(script.contains("BATCH_DIRECTORIES_BEFORE"));
        assertTrue(script.contains("BATCH_DIRECTORIES_AFTER"));
        assertTrue(script.contains("comm -13"));
        assertTrue(script.contains("${#new_batches[@]} -eq 1"));
        assertTrue(script.contains("--expected-batch-id"));
        assertTrue(script.contains("--expected-git-commit"));
        assertTrue(script.contains("--expected-model-digest"));
        assertTrue(script.contains("tar -C \"$results_root\" -czf \"$temporary_archive\" \"$batch_id\""));
        assertTrue(script.contains("LLMLogAnalyzer_FULL_BGL_${batch_id}.tar.gz"));
        assertTrue(script.contains("--queryFile=\"$query_file\""));
        assertTrue(script.contains("stream_exact_run_export \"$MONGODB_HYBRID_URI\" \"$hybrid_query\""));
        assertTrue(script.contains("stream_exact_run_export \"$MONGODB_PROMPT_ONLY_URI\" \"$prompt_query\""));
        assertTrue(script.contains("gzip -cd \"$partial\" | wc -l"));
        assertTrue(script.contains("[[ \"$exported_count\" != \"$expected_count\" ]]"));
    }

    @Test
    void validatorRequiresFullCoverageExactMethodsAndArtifactChecksums() throws Exception {
        String validator = readNormalized("scripts/validate_full_bgl_thesis_results.py");

        assertTrue(validator.contains("manifest.get(\"overallStatus\") == \"COMPLETED\""));
        assertTrue(validator.contains("manifest.get(\"evaluationScope\") == \"FULL_DATASET\""));
        assertTrue(validator.contains("manifest.get(\"officialThesisRun\") is True"));
        assertTrue(validator.contains("HYBRID_GUARD_AND_LLM"));
        assertTrue(validator.contains("PROMPT_ONLY_LLM"));
        assertTrue(validator.contains("processedRawRecords"));
        assertTrue(validator.contains("lastRecordIndex"));
        assertTrue(validator.contains("validate_checksums(batch / \"hybrid\")"));
        assertTrue(validator.contains("validate_checksums(batch / \"prompt_only\")"));
        assertTrue(validator.contains("validate_checksums(batch / \"comparison\")"));
    }

    private String readNormalized(String relativePath) throws Exception {
        return Files.readString(repository.resolve(relativePath)).replace("\r\n", "\n");
    }

    private int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
