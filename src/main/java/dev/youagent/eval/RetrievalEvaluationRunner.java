package dev.youagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.youagent.config.EmbeddingConfig;
import dev.youagent.search.EmbeddingModel;
import dev.youagent.search.FeatureHashEmbeddingModel;
import dev.youagent.search.OpenAiCompatibleEmbeddingModel;
import dev.youagent.search.SearchHit;
import dev.youagent.search.SearchMode;
import dev.youagent.search.SqliteCodeIndex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class RetrievalEvaluationRunner {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public RetrievalEvaluationReport run(Path projectRoot, EmbeddingConfig embeddingConfig) throws Exception {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path fixture = root.resolve("eval/retrieval/fixture");
        List<RetrievalGroundTruth> groundTruth = mapper.readValue(
                root.resolve("eval/retrieval/ground-truth.json").toFile(), new TypeReference<>() { });
        if (groundTruth.isEmpty()) {
            throw new IllegalStateException("retrieval ground truth must not be empty");
        }
        Path temporary = Files.createTempDirectory("you-agent-retrieval-eval-");
        List<RetrievalEvaluationReport.ConfigurationResult> configurations = new ArrayList<>();
        try {
            EmbeddingModel featureHash = new FeatureHashEmbeddingModel(embeddingConfig.featureHashDimensions());
            try (SqliteCodeIndex index = new SqliteCodeIndex(fixture, temporary.resolve("feature.db"), featureHash)) {
                index.rebuild();
                configurations.add(evaluate("keyword-only", featureHash.id(), index,
                        SearchMode.KEYWORD_ONLY, groundTruth));
                configurations.add(evaluate("feature-hash-hybrid", featureHash.id(), index,
                        SearchMode.HYBRID, groundTruth));
            }
            if (embeddingConfig.remoteConfigured()) {
                EmbeddingModel remote = new OpenAiCompatibleEmbeddingModel(embeddingConfig);
                try (SqliteCodeIndex index = new SqliteCodeIndex(fixture, temporary.resolve("remote.db"), remote)) {
                    index.rebuild();
                    configurations.add(evaluate("remote-embedding-hybrid", remote.id(), index,
                            SearchMode.HYBRID, groundTruth));
                }
            }
        } finally {
            deleteTree(temporary);
        }
        RetrievalEvaluationReport report = new RetrievalEvaluationReport(Instant.now(), gitCommit(root),
                System.getProperty("java.version"), System.getProperty("os.name") + " "
                + System.getProperty("os.arch"), configurations);
        write(root, report);
        return report;
    }

    private RetrievalEvaluationReport.ConfigurationResult evaluate(String name, String embeddingId,
            SqliteCodeIndex index, SearchMode mode, List<RetrievalGroundTruth> groundTruth) throws Exception {
        List<RetrievalEvaluationReport.QueryResult> results = new ArrayList<>();
        int recalled = 0;
        double reciprocalRanks = 0;
        double totalLatency = 0;
        for (RetrievalGroundTruth item : groundTruth) {
            long started = System.nanoTime();
            List<SearchHit> hits = index.search(item.query(), 10, mode);
            double latency = (System.nanoTime() - started) / 1_000_000.0;
            int rank = rank(hits, item.targetSymbol());
            if (rank > 0 && rank <= 5) {
                recalled++;
            }
            if (rank > 0 && rank <= 10) {
                reciprocalRanks += 1.0 / rank;
            }
            totalLatency += latency;
            results.add(new RetrievalEvaluationReport.QueryResult(item.id(), item.query(), item.targetSymbol(),
                    rank, latency, hits.stream().map(hit -> hit.chunk().symbol()).toList()));
        }
        int count = groundTruth.size();
        return new RetrievalEvaluationReport.ConfigurationResult(name, embeddingId, count,
                recalled / (double) count, reciprocalRanks / count, totalLatency / count, results);
    }

    private static int rank(List<SearchHit> hits, String targetSymbol) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).chunk().symbol().equals(targetSymbol)) {
                return i + 1;
            }
        }
        return 0;
    }

    private void write(Path root, RetrievalEvaluationReport report) throws IOException {
        Path results = root.resolve("eval/results");
        Files.createDirectories(results);
        mapper.writeValue(results.resolve("retrieval-latest.json").toFile(), report);
        StringBuilder markdown = new StringBuilder("# Code retrieval evaluation\n\n")
                .append("- Generated: `").append(report.generatedAt()).append("`\n")
                .append("- Git commit: `").append(report.gitCommit()).append("`\n")
                .append("- Runtime: Java `").append(report.javaVersion()).append("` on `")
                .append(report.operatingSystem()).append("`\n")
                .append("- Dataset: `eval/retrieval/ground-truth.json`\n\n")
                .append("| Configuration | Embedding | Queries | Recall@5 | MRR@10 | Avg latency |\n")
                .append("|---|---|---:|---:|---:|---:|\n");
        for (var configuration : report.configurations()) {
            markdown.append('|').append(configuration.name()).append('|')
                    .append(configuration.embeddingId()).append('|').append(configuration.queries()).append('|')
                    .append(format(configuration.recallAt5())).append('|')
                    .append(format(configuration.mrrAt10())).append('|')
                    .append(String.format(Locale.ROOT, "%.3f ms", configuration.averageLatencyMs())).append("|\n");
        }
        markdown.append("\nRanks use exact human-labeled JavaParser symbols; rank `0` means absent from the top 10.\n");
        Files.writeString(results.resolve("retrieval-latest.md"), markdown, StandardCharsets.UTF_8);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String gitCommit(Path root) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return "unknown";
            }
            return new String(process.getInputStream().readNBytes(128), StandardCharsets.UTF_8).strip();
        } catch (Exception failure) {
            return "unknown";
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
