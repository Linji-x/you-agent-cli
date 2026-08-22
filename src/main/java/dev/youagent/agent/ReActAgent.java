package dev.youagent.agent;

import dev.youagent.llm.ChatMessage;
import dev.youagent.llm.LlmClient;
import dev.youagent.llm.LlmResponse;
import dev.youagent.llm.ToolCall;
import dev.youagent.memory.ContextCompactor;
import dev.youagent.tool.ToolExecution;
import dev.youagent.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class ReActAgent {
    private final LlmClient client;
    private final ToolRegistry tools;
    private final int maxRounds;
    private final String systemPrompt;
    private final BooleanSupplier cancelled;
    private final ContextCompactor compactor;

    public ReActAgent(LlmClient client, ToolRegistry tools, int maxRounds, String systemPrompt) {
        this(client, tools, maxRounds, systemPrompt, () -> false, null);
    }

    public ReActAgent(LlmClient client, ToolRegistry tools, int maxRounds, String systemPrompt,
                      BooleanSupplier cancelled) {
        this(client, tools, maxRounds, systemPrompt, cancelled, null);
    }

    public ReActAgent(LlmClient client, ToolRegistry tools, int maxRounds, String systemPrompt,
                      BooleanSupplier cancelled, ContextCompactor compactor) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be positive");
        }
        this.client = client;
        this.tools = tools;
        this.maxRounds = maxRounds;
        this.systemPrompt = systemPrompt;
        this.cancelled = cancelled;
        this.compactor = compactor;
    }

    public AgentResult run(String task) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system(systemPrompt));
        history.add(ChatMessage.user(task));
        List<AgentEvent> events = new ArrayList<>();
        Map<String, Integer> failedFingerprints = new HashMap<>();

        for (int round = 1; round <= maxRounds; round++) {
            if (cancelled.getAsBoolean()) {
                return terminate(AgentResult.ExitReason.CANCELLED, "Task cancelled", round - 1, events, history);
            }
            if (compactor != null) {
                ContextCompactor.CompressionResult compression = compactor.compactIfNeeded(history);
                if (!compression.messages().equals(history)) {
                    history.clear();
                    history.addAll(compression.messages());
                }
                if (compression.compressed()) {
                    events.add(new AgentEvent(AgentEvent.Type.CONTEXT_COMPACTED, round, "context",
                            compression.tokensBefore() + " -> " + compression.tokensAfter() + " estimated tokens", true));
                }
            }
            events.add(new AgentEvent(AgentEvent.Type.MODEL_REQUEST, round, "llm", "history=" + history.size(), true));
            LlmResponse response;
            try {
                response = client.complete(List.copyOf(history), tools.definitions());
            } catch (IOException | RuntimeException failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                return terminate(AgentResult.ExitReason.CLIENT_ERROR, message, round, events, history);
            }
            events.add(new AgentEvent(AgentEvent.Type.MODEL_RESPONSE, round, "llm",
                    "tool_calls=" + response.toolCalls().size(), true));
            history.add(ChatMessage.assistant(response.content(), response.toolCalls()));

            if (response.toolCalls().isEmpty()) {
                if (response.finishReason() == LlmResponse.FinishReason.LENGTH) {
                    return terminate(AgentResult.ExitReason.LENGTH_LIMIT, response.content(), round, events, history);
                }
                if (response.content().isBlank()) {
                    return terminate(AgentResult.ExitReason.EMPTY_RESPONSE, "Model returned no content or tools",
                            round, events, history);
                }
                return terminate(AgentResult.ExitReason.COMPLETED, response.content(), round, events, history);
            }

            for (ToolCall call : response.toolCalls()) {
                events.add(new AgentEvent(AgentEvent.Type.TOOL_CALL, round, call.name(), call.arguments().toString(), true));
                ToolExecution execution = tools.execute(call);
                events.add(new AgentEvent(AgentEvent.Type.TOOL_RESULT, round, call.name(),
                        execution.toModelText(), execution.success()));
                history.add(ChatMessage.tool(call.id(), execution.toModelText()));
                if (!execution.success()) {
                    String fingerprint = call.name() + "|" + call.arguments() + "|" + execution.errorCode();
                    int attempts = failedFingerprints.merge(fingerprint, 1, Integer::sum);
                    if (attempts >= 3) {
                        return terminate(AgentResult.ExitReason.REPEATED_FAILURE,
                                "Stopped after three identical failed tool calls", round, events, history);
                    }
                }
            }
        }
        return terminate(AgentResult.ExitReason.MAX_ROUNDS,
                "Stopped after reaching maxRounds=" + maxRounds, maxRounds, events, history);
    }

    private AgentResult terminate(AgentResult.ExitReason reason, String answer, int rounds,
                                  List<AgentEvent> events, List<ChatMessage> history) {
        events.add(new AgentEvent(AgentEvent.Type.TERMINATED, rounds, reason.name(), answer,
                reason == AgentResult.ExitReason.COMPLETED));
        return new AgentResult(reason, answer, rounds, events, history);
    }
}
