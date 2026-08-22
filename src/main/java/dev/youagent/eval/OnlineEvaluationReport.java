package dev.youagent.eval;

import java.time.Instant;
import java.util.List;

public record OnlineEvaluationReport(
        Instant generatedAt,
        String gitCommit,
        String javaVersion,
        String operatingSystem,
        String model,
        int total,
        int passed,
        double completionRate,
        List<CaseResult> results
) {
    public OnlineEvaluationReport {
        results = List.copyOf(results);
    }

    public record CaseResult(String id, String category, boolean passed, int rounds, int toolCalls,
                             long durationMs, int estimatedTokens, String verification, String error) {
    }
}
