package dev.youagent.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

public record EmbeddingConfig(URI baseUri, String apiKey, String model, int featureHashDimensions) {
    public static EmbeddingConfig load(Path workspace) throws IOException {
        Map<String, String> file = EnvLoader.load(workspace.toAbsolutePath().normalize().resolve(".env"));
        return new EmbeddingConfig(
                URI.create(value("YOU_AGENT_EMBEDDING_BASE_URL", file, "https://api.example.com/v1")),
                value("YOU_AGENT_EMBEDDING_API_KEY", file, ""),
                value("YOU_AGENT_EMBEDDING_MODEL", file, ""),
                positiveInt("YOU_AGENT_FEATURE_HASH_DIMENSIONS", file, 256)
        );
    }

    public boolean remoteConfigured() {
        return !apiKey.isBlank() && !model.isBlank() && !baseUri.toString().contains("api.example.com");
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
            int value = Integer.parseInt(value(name, file, Integer.toString(fallback)));
            return value >= 32 && value <= 4096 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
