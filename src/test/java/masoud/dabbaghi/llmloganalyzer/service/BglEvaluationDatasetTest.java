package masoud.dabbaghi.llmloganalyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglEvaluationDatasetTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesAndExcludesExactDevelopmentLines() throws IOException {
        Path dataset = tempDir.resolve("BGL.log");
        Path development = tempDir.resolve("BGL_2k.log");
        Files.writeString(dataset, "keep-1\nexclude-a\nkeep-2\nexclude-b\n");
        Files.writeString(development, "exclude-a\nexclude-b\n");

        BglEvaluationDataset.ExclusionPlan plan =
                BglEvaluationDataset.loadExclusion(true, development.toString());
        BglEvaluationDataset.PreflightResult result =
                BglEvaluationDataset.preflight(dataset, plan);

        assertEquals(4, result.sourceLineCount());
        assertEquals(2, result.evaluationLineCount());
        assertEquals(2, result.excludedLineCount());
        assertEquals(64, result.evaluationSha256().length());

        BglEvaluationDataset.LineExcluder excluder =
                BglEvaluationDataset.newLineExcluder(plan);
        assertTrue(excluder.shouldExclude("exclude-a"));
        assertTrue(excluder.shouldExclude("exclude-b"));
        excluder.verifyComplete();
        assertEquals(2, excluder.excludedLineCount());
    }

    @Test
    void rejectsMissingDevelopmentLine() throws IOException {
        Path dataset = tempDir.resolve("BGL.log");
        Path development = tempDir.resolve("BGL_2k.log");
        Files.writeString(dataset, "keep\nexclude-a\n");
        Files.writeString(development, "exclude-a\nmissing\n");

        BglEvaluationDataset.ExclusionPlan plan =
                BglEvaluationDataset.loadExclusion(true, development.toString());

        assertThrows(
                IllegalStateException.class,
                () -> BglEvaluationDataset.preflight(dataset, plan)
        );
    }

    @Test
    void rejectsAmbiguousExtraDuplicateMatch() throws IOException {
        Path dataset = tempDir.resolve("BGL.log");
        Path development = tempDir.resolve("BGL_2k.log");
        Files.writeString(dataset, "exclude-a\nkeep\nexclude-a\n");
        Files.writeString(development, "exclude-a\n");

        BglEvaluationDataset.ExclusionPlan plan =
                BglEvaluationDataset.loadExclusion(true, development.toString());

        assertThrows(
                IllegalStateException.class,
                () -> BglEvaluationDataset.preflight(dataset, plan)
        );
    }
}
