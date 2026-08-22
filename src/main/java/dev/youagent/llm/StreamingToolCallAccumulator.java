package dev.youagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StreamingToolCallAccumulator {
    private final ObjectMapper mapper;
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, PartialCall> calls = new LinkedHashMap<>();
    private LlmResponse.FinishReason finishReason = LlmResponse.FinishReason.UNKNOWN;

    public StreamingToolCallAccumulator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void accept(JsonNode chunk) {
        JsonNode choice = chunk.path("choices").path(0);
        if (choice.isMissingNode()) {
            return;
        }
        JsonNode delta = choice.path("delta");
        if (delta.path("content").isTextual()) {
            content.append(delta.path("content").asText());
        }
        for (JsonNode call : delta.path("tool_calls")) {
            int index = call.path("index").asInt(0);
            PartialCall partial = calls.computeIfAbsent(index, ignored -> new PartialCall());
            append(partial.id, call.path("id"));
            JsonNode function = call.path("function");
            append(partial.name, function.path("name"));
            append(partial.arguments, function.path("arguments"));
        }
        if (choice.path("finish_reason").isTextual()) {
            finishReason = switch (choice.path("finish_reason").asText()) {
                case "stop" -> LlmResponse.FinishReason.STOP;
                case "tool_calls" -> LlmResponse.FinishReason.TOOL_CALLS;
                case "length" -> LlmResponse.FinishReason.LENGTH;
                default -> LlmResponse.FinishReason.UNKNOWN;
            };
        }
    }

    public LlmResponse finish() throws IOException {
        List<ToolCall> completed = new ArrayList<>();
        calls.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(entry -> completed.add(toToolCall(entry.getValue(), entry.getKey())));
        return new LlmResponse(content.toString(), completed, finishReason);
    }

    private ToolCall toToolCall(PartialCall partial, int index) {
        String raw = partial.arguments.toString();
        try {
            JsonNode arguments = raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
            return new ToolCall(defaultIfBlank(partial.id.toString(), "call-" + index),
                    partial.name.toString(), arguments);
        } catch (IOException invalidJson) {
            throw new IllegalArgumentException("Model returned invalid JSON arguments for tool index " + index,
                    invalidJson);
        }
    }

    private static void append(StringBuilder target, JsonNode value) {
        if (value.isTextual()) {
            target.append(value.asText());
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private static final class PartialCall {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
