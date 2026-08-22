package dev.youagent.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public record AppConfig(
        URI baseUri,
        String apiKey,
        String model,
        int maxRounds,
        Duration commandTimeout,
        int contextBudgetTokens,
        int compressionTriggerPercent,
        Path memoryFile,
        Path indexFile
) {
    public static AppConfig load(Path workspace) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Map<String, String> file = EnvLoader.load(root.resolve(".env"));
        return new AppConfig(
                URI.create(value("YOU_AGENT_BASE_URL", file, "https://api.example.com/v1")),
                value("YOU_AGENT_API_KEY", file, ""),
                value("YOU_AGENT_MODEL", file, ""),
                positiveInt("YOU_AGENT_MAX_ROUNDS", file, 10),
                Duration.ofSeconds(positiveInt("YOU_AGENT_COMMAND_TIMEOUT_SECONDS", file, 30)),
                positiveInt("YOU_AGENT_CONTEXT_BUDGET_TOKENS", file, 32_000),
                boundedPercent("YOU_AGENT_COMPRESSION_TRIGGER_PERCENT", file, 80),
                resolveInside(root, value("YOU_AGENT_MEMORY_FILE", file, ".you-agent/memory.jsonl")),
                resolveInside(root, value("YOU_AGENT_INDEX_FILE", file, ".you-agent/code-index.db"))
        );
    }

    public boolean hasProviderCredentials() {
        return !apiKey.isBlank() && !model.isBlank()
                && !baseUri.toString().contains("api.example.com");
    }

    private static String value(String name, Map<String, String> file, String fallback) {
        String environment = System.getenv(name);
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }
        String fromFile = file.get(name);
        return fromFile == null || fromFile.isBlank() ? fallback : fromFile.trim();
    }

    private static int positiveInt(String name, Map<String, String> file, int fallback) {
        try {
            int parsed = Integer.parseInt(value(name, file, Integer.toString(fallback)));
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int boundedPercent(String name, Map<String, String> file, int fallback) {
        int parsed = positiveInt(name, file, fallback);
        return parsed >= 20 && parsed <= 95 ? parsed : fallback;
    }

    private static Path resolveInside(Path root, String configured) {
        Path path = Path.of(configured);
        Path resolved = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(nameFor(configured) + " must stay inside the workspace");
        }
        return resolved;
    }

    private static String nameFor(String configured) {
        return "Configured path '" + configured + "'";
    }
}
