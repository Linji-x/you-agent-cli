package dev.youagent.benchmark;

import java.time.Instant;
import java.util.List;

public record BenchmarkReport(
        Instant generatedAt,
        String javaVersion,
        String os,
        int total,
        int passed,
        List<BenchmarkResult> results
) {
    public BenchmarkReport {
        results = List.copyOf(results);
    }
}
