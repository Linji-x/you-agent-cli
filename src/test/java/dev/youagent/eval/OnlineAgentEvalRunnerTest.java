package dev.youagent.eval;

import dev.youagent.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OnlineAgentEvalRunnerTest {
    @Test
    void refusesToCreateResultsWithoutRealProviderConfiguration(@TempDir Path project) {
        AppConfig config = new AppConfig(URI.create("https://api.example.com/v1"), "", "", 3,
                Duration.ofSeconds(1), 1_000, 80, 2,
                project.resolve("memory.jsonl"), project.resolve("index.db"));

        assertThrows(IllegalStateException.class, () -> new OnlineAgentEvalRunner().run(project, config));
        assertFalse(Files.exists(project.resolve("eval/results/online-latest.json")));
    }
}
