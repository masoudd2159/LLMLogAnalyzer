package masoud.dabbaghi.llmloganalyzer.controller;

import masoud.dabbaghi.llmloganalyzer.service.BglParser;
import masoud.dabbaghi.llmloganalyzer.service.CallModelAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;


@RestController
public class BglController {

    @Autowired
    private BglParser bglParser;
    @Autowired
    private CallModelAi callModelAi;

    @Value("${model.api.gpt.url}")
    private String gptApiURL;

    @Value("${model.api.ollama.url}")
    private String ollamaApiUrl;

    @Value("${model.api.gpt.model-name}")
    private String gptModel;

    @Value("${model.api.ollama.model-name}")
    private String ollamaModel;


    @GetMapping("/bgl")
    public ResponseEntity<String> bgl() throws IOException {
        bglParser.logParser();
        return ResponseEntity.ok().build();
    }
}
