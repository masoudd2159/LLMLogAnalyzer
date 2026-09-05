package masoud.dabbaghi.llmloganalyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

@Service
public class BglDatasetPreflightService {

    private final ObjectMapper objectMapper;
    private final Path datasetPath;
    private final Path outputDirectory;

    public BglDatasetPreflightService(
            ObjectMapper objectMapper,
            @Value("${bgl.location}") String datasetPath,
            @Value("${charts.output-dir:results}") String outputDirectory
    ) {
        this.objectMapper = objectMapper;
        this.datasetPath = Path.of(datasetPath).toAbsolutePath().normalize();
        this.outputDirectory = Path.of(outputDirectory).toAbsolutePath().normalize();
    }

    public BglDatasetPreflightReport inspectAndWriteReport() throws IOException {
        if (!Files.isRegularFile(datasetPath) || !Files.isReadable(datasetPath)) {
            throw new IOException("BGL dataset is missing or unreadable: " + datasetPath);
        }

        LongAdder raw = new LongAdder();
        LongAdder parsed = new LongAdder();
        LongAdder errors = new LongAdder();
        LongAdder normal = new LongAdder();
        LongAdder anomaly = new LongAdder();

        try (Stream<String> lines = Files.lines(datasetPath)) {
            lines.forEach(line -> {
                raw.increment();
                LogBglEntryDto entry = BglParser.parseLine(line);
                if (entry == null) {
                    errors.increment();
                } else {
                    parsed.increment();
                    if ("-".equals(entry.getLabel())) {
                        normal.increment();
                    } else {
                        anomaly.increment();
                    }
                }
            });
        }

        BglDatasetPreflightReport report = new BglDatasetPreflightReport(
                datasetPath.toString(),
                sha256(datasetPath),
                Files.size(datasetPath),
                raw.sum(),
                parsed.sum(),
                errors.sum(),
                normal.sum(),
                anomaly.sum(),
                Instant.now()
        );
        Files.createDirectories(outputDirectory);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputDirectory.resolve("bgl_preprocessing_report.json").toFile(), report);
        return report;
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
