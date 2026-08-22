package dev.youagent.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BenchmarkRunner {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final BenchmarkScenarios scenarios = new BenchmarkScenarios();

    public BenchmarkReport run(Path projectRoot) throws IOException {
        Path tasksFile = projectRoot.resolve("benchmarks/tasks.json");
        List<BenchmarkCase> cases = mapper.readValue(tasksFile.toFile(), new TypeReference<>() { });
        if (cases.size() < 20 || cases.size() > 30) {
            throw new IllegalStateException("benchmark must contain 20-30 fixed tasks, found " + cases.size());
        }
        List<BenchmarkResult> results = new ArrayList<>();
        for (BenchmarkCase benchmark : cases) {
            long started = System.nanoTime();
            try {
                String evidence = scenarios.run(benchmark.id());
                results.add(new BenchmarkResult(benchmark.id(), benchmark.category(), true,
                        elapsedMs(started), evidence, ""));
            } catch (Exception | AssertionError failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                results.add(new BenchmarkResult(benchmark.id(), benchmark.category(), false,
                        elapsedMs(started), "", message));
            }
        }
        int passed = (int) results.stream().filter(BenchmarkResult::passed).count();
        BenchmarkReport report = new BenchmarkReport(Instant.now(), System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.arch"), results.size(), passed, results);
        writeResults(projectRoot, report);
        return report;
    }

    private void writeResults(Path projectRoot, BenchmarkReport report) throws IOException {
        Path resultsDirectory = projectRoot.resolve("benchmarks/results");
        Files.createDirectories(resultsDirectory);
        mapper.writeValue(resultsDirectory.resolve("latest.json").toFile(), report);
        StringBuilder markdown = new StringBuilder("# Deterministic offline conformance benchmark results\n\n")
                .append("- Generated: `").append(report.generatedAt()).append("`\n")
                .append("- Runtime: Java `").append(report.javaVersion()).append("` on `").append(report.os()).append("`\n")
                .append("- Result: **").append(report.passed()).append('/').append(report.total()).append(" passed**\n\n")
                .append("| ID | Area | Result | Time | Evidence |\n")
                .append("|---|---|---:|---:|---|\n");
        for (BenchmarkResult result : report.results()) {
            markdown.append('|').append(result.id()).append('|').append(result.category()).append('|')
                    .append(result.passed() ? "PASS" : "FAIL").append('|')
                    .append(result.durationMs()).append(" ms|")
                    .append(escape(result.passed() ? result.evidence() : result.error())).append("|\n");
        }
        Files.writeString(resultsDirectory.resolve("latest.md"), markdown, StandardCharsets.UTF_8);
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replaceAll("\\s+", " ").strip();
    }
}
