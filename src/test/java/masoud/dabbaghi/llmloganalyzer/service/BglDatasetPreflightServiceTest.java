package masoud.dabbaghi.llmloganalyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglDatasetPreflightServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesDatasetIdentityAndParserCoverage() throws Exception {
        Path dataset = temporaryDirectory.resolve("BGL.log");
        Files.write(dataset, List.of(
                "- 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-15.42.50.675872 R02-M1-N0-C:J12-U11 RAS KERNEL INFO generating core.1234",
                "KERNDTLB 1117838571 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-15.42.51.675872 R02-M1-N0-C:J12-U11 RAS KERNEL FATAL data TLB error interrupt",
                "not a BGL row"
        ));
        Path output = temporaryDirectory.resolve("results");
        BglDatasetPreflightService service = new BglDatasetPreflightService(
                new ObjectMapper().findAndRegisterModules(),
                dataset.toString(),
                output.toString()
        );

        BglDatasetPreflightReport report = service.inspectAndWriteReport();

        assertEquals(3, report.rawLines());
        assertEquals(2, report.parsedLines());
        assertEquals(1, report.parseErrors());
        assertEquals(1, report.normalLabels());
        assertEquals(1, report.anomalyLabels());
        assertEquals(64, report.sha256().length());
        assertTrue(Files.isRegularFile(output.resolve("bgl_preprocessing_report.json")));
    }
}
