package dev.youagent.benchmark;

public record BenchmarkResult(String id, String category, boolean passed, long durationMs,
                              String evidence, String error) {
}
