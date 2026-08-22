package dev.youagent.llm;

import java.util.List;

public record LlmResponse(String content, List<ToolCall> toolCalls, FinishReason finishReason) {
    public LlmResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? FinishReason.UNKNOWN : finishReason;
    }

    public enum FinishReason {
        STOP,
        TOOL_CALLS,
        LENGTH,
        UNKNOWN
    }
}
