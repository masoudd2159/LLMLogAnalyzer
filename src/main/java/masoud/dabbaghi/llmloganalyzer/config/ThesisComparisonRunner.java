package masoud.dabbaghi.llmloganalyzer.config;

import lombok.RequiredArgsConstructor;
import masoud.dabbaghi.llmloganalyzer.thesis.ThesisComparisonService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("thesis-comparison")
@RequiredArgsConstructor
public class ThesisComparisonRunner implements CommandLineRunner {
    private final ThesisComparisonService service;
    @Override public void run(String... args) throws Exception { service.compare(); }
}
