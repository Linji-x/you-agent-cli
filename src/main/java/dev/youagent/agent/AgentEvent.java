package dev.youagent.agent;

public record AgentEvent(Type type, int round, String name, String detail, boolean success) {
    public enum Type {
        MODEL_REQUEST,
        MODEL_RESPONSE,
        CONTEXT_COMPACTED,
        TOOL_CALL,
        TOOL_RESULT,
        TERMINATED
    }
}
