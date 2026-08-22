package dev.youagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class LongTermMemory {
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public LongTermMemory(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public synchronized MemoryFact save(String scope, String content) throws IOException {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("memory content is required");
        }
        Files.createDirectories(file.getParent());
        MemoryFact fact = new MemoryFact(UUID.randomUUID().toString(), scope.strip(), content.strip(), Instant.now());
        Files.writeString(file, mapper.writeValueAsString(fact) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return fact;
    }

    public synchronized List<MemoryFact> listVisible(String projectScope) throws IOException {
        return load().stream()
                .filter(fact -> fact.scope().equals("global") || fact.scope().equals(projectScope))
                .sorted(Comparator.comparing(MemoryFact::createdAt).reversed())
                .toList();
    }

    public synchronized List<MemoryFact> search(String projectScope, String query, int limit) throws IOException {
        Set<String> terms = tokenize(query);
        return listVisible(projectScope).stream()
                .map(fact -> new ScoredFact(fact, score(fact.content(), terms)))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingInt(ScoredFact::score).reversed()
                        .thenComparing(scored -> scored.fact.createdAt(), Comparator.reverseOrder()))
                .limit(Math.max(1, limit))
                .map(ScoredFact::fact)
                .toList();
    }

    public synchronized boolean delete(String id) throws IOException {
        List<MemoryFact> facts = load();
        boolean removed = facts.removeIf(fact -> fact.id().equals(id));
        if (removed) {
            rewrite(facts);
        }
        return removed;
    }

    private List<MemoryFact> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        List<MemoryFact> facts = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                facts.add(mapper.readValue(line, MemoryFact.class));
            }
        }
        return facts;
    }

    private void rewrite(List<MemoryFact> facts) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder content = new StringBuilder();
        for (MemoryFact fact : facts) {
            content.append(mapper.writeValueAsString(fact)).append(System.lineSeparator());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null) {
            return terms;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (!token.isBlank()) {
                terms.add(token);
                addCjkNgrams(token, terms);
            }
        }
        return terms;
    }

    private static void addCjkNgrams(String token, Set<String> terms) {
        List<Integer> run = new ArrayList<>();
        token.codePoints().forEach(codePoint -> {
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                run.add(codePoint);
            } else {
                addRunNgrams(run, terms);
                run.clear();
            }
        });
        addRunNgrams(run, terms);
    }

    private static void addRunNgrams(List<Integer> run, Set<String> terms) {
        if (run.isEmpty()) {
            return;
        }
        for (int size = 1; size <= Math.min(3, run.size()); size++) {
            for (int start = 0; start + size <= run.size(); start++) {
                StringBuilder value = new StringBuilder();
                for (int i = start; i < start + size; i++) {
                    value.appendCodePoint(run.get(i));
                }
                terms.add(value.toString());
            }
        }
    }

    private static int score(String content, Set<String> queryTerms) {
        String lower = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : queryTerms) {
            if (lower.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private record ScoredFact(MemoryFact fact, int score) {
    }
}
