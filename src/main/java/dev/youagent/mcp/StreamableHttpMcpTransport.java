package dev.youagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StreamableHttpMcpTransport implements McpTransport {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final URI endpoint;
    private final Map<String, String> headers;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private volatile String sessionId;
    private volatile boolean started;

    public StreamableHttpMcpTransport(URI endpoint, Map<String, String> headers) {
        this(endpoint, headers, new OkHttpClient.Builder().build());
    }

    StreamableHttpMcpTransport(URI endpoint, Map<String, String> headers, OkHttpClient http) {
        if (!endpoint.getScheme().equals("http") && !endpoint.getScheme().equals("https")) {
            throw new IllegalArgumentException("MCP HTTP endpoint must use http or https");
        }
        this.endpoint = endpoint;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.http = http;
    }

    @Override
    public void start(Duration timeout) {
        started = true;
    }

    @Override
    public JsonNode request(ObjectNode message, Duration timeout) throws IOException {
        ensureStarted();
        try (Response response = execute(message, timeout)) {
            if (!response.isSuccessful()) {
                throw new IOException("MCP HTTP request failed with status " + response.code());
            }
            captureSession(response);
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("MCP HTTP response body was empty");
            }
            String contentType = response.header("Content-Type", "");
            String raw = body.string();
            return contentType.contains("text/event-stream") ? parseEventStream(raw, message.path("id")) : mapper.readTree(raw);
        }
    }

    @Override
    public void notify(ObjectNode message) throws IOException {
        ensureStarted();
        try (Response response = execute(message, Duration.ofSeconds(15))) {
            if (!response.isSuccessful() && response.code() != 202) {
                throw new IOException("MCP HTTP notification failed with status " + response.code());
            }
            captureSession(response);
        }
    }

    @Override
    public void close() {
        started = false;
        sessionId = null;
    }

    private Response execute(ObjectNode message, Duration timeout) throws IOException {
        OkHttpClient timed = http.newBuilder().callTimeout(timeout).build();
        Request.Builder request = new Request.Builder()
                .url(endpoint.toString())
                .header("Accept", "application/json, text/event-stream")
                .post(RequestBody.create(mapper.writeValueAsBytes(message), JSON));
        Map<String, String> combined = new LinkedHashMap<>(headers);
        if (sessionId != null) {
            combined.put("Mcp-Session-Id", sessionId);
        }
        combined.forEach(request::header);
        return timed.newCall(request.build()).execute();
    }

    private JsonNode parseEventStream(String raw, JsonNode expectedId) throws IOException {
        for (String line : raw.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            JsonNode candidate = mapper.readTree(line.substring(5).trim());
            if (!candidate.has("id") || candidate.path("id").asText().equals(expectedId.asText())) {
                return candidate;
            }
        }
        throw new IOException("MCP event stream did not contain a matching response");
    }

    private void captureSession(Response response) {
        String value = response.header("Mcp-Session-Id");
        if (value != null && !value.isBlank()) {
            sessionId = value;
        }
    }

    private void ensureStarted() throws IOException {
        if (!started) {
            throw new IOException("MCP HTTP transport is not started");
        }
    }
}
