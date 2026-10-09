package dev.youagent.eval;

import dev.youagent.config.EmbeddingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvaluationRunnerTest {
    @Test
    void calculatesMetricsFromRealRankedHitsAndWritesReports(@TempDir Path project) throws Exception {
        Path fixture = project.resolve("eval/retrieval/fixture/src/A.java");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, """
                public final class A {
                    public boolean findToken(String token) { return token != null; }
                }
                """);
        Path dataset = project.resolve("eval/retrieval/ground-truth.json");
        Files.createDirectories(dataset.getParent());
        Files.writeString(dataset, """
                [{"id":"q1","query":"find token","targetSymbol":"A#findToken(String)"}]
                """);

        RetrievalEvaluationReport report = new RetrievalEvaluationRunner().run(project,
                new EmbeddingConfig(URI.create("https://api.example.com/v1"), "", "", 128));

        assertEquals(2, report.configurations().size());
        assertEquals(1.0, report.configurations().get(0).recallAt5());
        assertEquals(1.0, report.configurations().get(1).mrrAt10());
        assertTrue(Files.isRegularFile(project.resolve("eval/results/retrieval-latest.json")));
        assertTrue(Files.isRegularFile(project.resolve("eval/results/retrieval-latest.md")));
    }
}
