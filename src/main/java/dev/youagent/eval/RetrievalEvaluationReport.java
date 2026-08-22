package dev.youagent.eval;

import java.time.Instant;
import java.util.List;

public record RetrievalEvaluationReport(
        Instant generatedAt,
        String gitCommit,
        String javaVersion,
        String operatingSystem,
        List<ConfigurationResult> configurations
) {
    public RetrievalEvaluationReport {
        configurations = List.copyOf(configurations);
    }

    public record ConfigurationResult(String name, String embeddingId, int queries,
                                      double recallAt5, double mrrAt10, double averageLatencyMs,
                                      List<QueryResult> results) {
        public ConfigurationResult {
            results = List.copyOf(results);
        }
    }

    public record QueryResult(String id, String query, String targetSymbol, int rank,
                              double latencyMs, List<String> topSymbols) {
        public QueryResult {
            topSymbols = List.copyOf(topSymbols);
        }
    }
}
