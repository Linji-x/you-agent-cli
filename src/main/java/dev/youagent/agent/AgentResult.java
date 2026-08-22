package dev.youagent.agent;

import dev.youagent.llm.ChatMessage;

import java.util.List;

public record AgentResult(
        ExitReason exitReason,
        String answer,
        int rounds,
        List<AgentEvent> events,
        List<ChatMessage> history
) {
    public AgentResult {
        answer = answer == null ? "" : answer;
        events = List.copyOf(events);
        history = List.copyOf(history);
    }

    public boolean completed() {
        return exitReason == ExitReason.COMPLETED;
    }

    public enum ExitReason {
        COMPLETED,
        MAX_ROUNDS,
        REPEATED_FAILURE,
        EMPTY_RESPONSE,
        LENGTH_LIMIT,
        CLIENT_ERROR,
        CANCELLED
    }
}
