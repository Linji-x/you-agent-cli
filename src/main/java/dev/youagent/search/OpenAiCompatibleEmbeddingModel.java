package dev.youagent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.config.EmbeddingConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;

public final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final EmbeddingConfig config;
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public OpenAiCompatibleEmbeddingModel(EmbeddingConfig config) {
        this(config, new ObjectMapper(), new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(60)).build());
    }

    OpenAiCompatibleEmbeddingModel(EmbeddingConfig config, ObjectMapper mapper, OkHttpClient http) {
        if (!config.remoteConfigured()) {
            throw new IllegalArgumentException("remote embedding configuration is incomplete");
        }
        this.config = config;
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public double[] embed(String text) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", config.model());
        payload.put("input", text == null ? "" : text);
        Request request = new Request.Builder()
                .url(endpoint())
                .header("Authorization", "Bearer " + config.apiKey())
                .post(RequestBody.create(mapper.writeValueAsBytes(payload), JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Embedding request failed with HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new IOException("Embedding response body was empty");
            }
            JsonNode values = mapper.readTree(response.body().bytes()).path("data").path(0).path("embedding");
            if (!values.isArray() || values.isEmpty()) {
                throw new IOException("Embedding response omitted data[0].embedding");
            }
            double[] vector = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i).asDouble();
            }
            return vector;
        }
    }

    @Override
    public String id() {
        return "openai-compatible:" + config.baseUri() + ":" + config.model();
    }

    private String endpoint() {
        String base = config.baseUri().toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/embeddings";
    }
}
