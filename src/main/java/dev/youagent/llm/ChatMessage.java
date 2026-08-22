package dev.youagent.llm;

import java.util.List;

public record ChatMessage(
        String role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls
) {
    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, List.of());
    }

    public static ChatMessage assistant(String content, List<ToolCall> calls) {
        return new ChatMessage("assistant", content, null, calls);
    }

    public static ChatMessage tool(String callId, String content) {
        return new ChatMessage("tool", content, callId, List.of());
    }
}
