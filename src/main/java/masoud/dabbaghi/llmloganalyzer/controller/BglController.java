package masoud.dabbaghi.llmloganalyzer.controller;

import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import masoud.dabbaghi.llmloganalyzer.service.BglParser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class BglController {

    private final BglParser bglParser;

    public BglController(BglParser bglParser) {
        this.bglParser = bglParser;
    }

    /** Runs one complete, isolated experiment and returns its persisted metadata. */
    @PostMapping("/api/bgl/runs")
    public ResponseEntity<BglExperimentRun> runExperiment() throws IOException {
        return ResponseEntity.ok(bglParser.logParser());
    }

    /** Backward-compatible endpoint retained from master. */
    @GetMapping("/bgl")
    public ResponseEntity<Void> runLegacyExperiment() throws IOException {
        bglParser.logParser();
        return ResponseEntity.ok().build();
    }
}
