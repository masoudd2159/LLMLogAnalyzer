package masoud.dabbaghi.llmloganalyzer.controller;

import masoud.dabbaghi.llmloganalyzer.evaluation.BglExperimentRun;
import masoud.dabbaghi.llmloganalyzer.service.BglParser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/bgl")
public class BglController {

    private final BglParser bglParser;

    public BglController(BglParser bglParser) {
        this.bglParser = bglParser;
    }

    /** Runs one complete, isolated experiment and returns its persisted metadata. */
    @PostMapping("/runs")
    public ResponseEntity<BglExperimentRun> runExperiment() throws IOException {
        return ResponseEntity.ok(bglParser.logParser());
    }
}
