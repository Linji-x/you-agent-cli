package dev.youagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.config.AppConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public final class OpenAiCompatibleClient implements LlmClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final AppConfig config;
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public OpenAiCompatibleClient(AppConfig config) {
        this(config, new ObjectMapper(), new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofSeconds(30))
                .build());
    }

    OpenAiCompatibleClient(AppConfig config, ObjectMapper mapper, OkHttpClient http) {
        this.config = config;
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", config.model());
        payload.put("stream", true);
        payload.set("messages", messages(messages));
        if (!tools.isEmpty()) {
            payload.set("tools", tools(tools));
            payload.put("tool_choice", "auto");
        }

        Request request = new Request.Builder()
                .url(endpoint())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(mapper.writeValueAsBytes(payload), JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("LLM request failed with HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("LLM response body was empty");
            }
            return parseStream(body.source());
        }
    }

    private LlmResponse parseStream(BufferedSource source) throws IOException {
        StreamingToolCallAccumulator accumulator = new StreamingToolCallAccumulator(mapper);
        String line;
        while ((line = source.readUtf8Line()) != null) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring(5).trim();
            if (data.equals("[DONE]")) {
                break;
            }
            if (!data.isEmpty()) {
                accumulator.accept(mapper.readTree(data));
            }
        }
        return accumulator.finish();
    }

    private ArrayNode messages(List<ChatMessage> history) {
        ArrayNode array = mapper.createArrayNode();
        for (ChatMessage message : history) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments().toString());
                }
            }
        }
        return array;
    }

    private ArrayNode tools(List<ToolDefinition> definitions) {
        ArrayNode array = mapper.createArrayNode();
        for (ToolDefinition definition : definitions) {
            ObjectNode tool = array.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", definition.inputSchema());
        }
        return array;
    }

    private String endpoint() {
        String base = config.baseUri().toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/chat/completions";
    }
}
