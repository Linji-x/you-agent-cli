package dev.youagent.memory;

import dev.youagent.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public final class ContextCompactor {
    @FunctionalInterface
    public interface Summarizer {
        String summarize(List<ChatMessage> messages);
    }

    public record CompressionResult(boolean compressed, int tokensBefore, int tokensAfter,
                                    List<ChatMessage> messages) {
        public CompressionResult {
            messages = List.copyOf(messages);
        }
    }

    private final int tokenBudget;
    private final int triggerPercent;
    private final int retainedUserTurns;
    private final int maxToolChars;
    private final Summarizer summarizer;

    public ContextCompactor(int tokenBudget, int triggerPercent, int retainedUserTurns,
                            int maxToolChars, Summarizer summarizer) {
        this.tokenBudget = tokenBudget;
        this.triggerPercent = triggerPercent;
        this.retainedUserTurns = retainedUserTurns;
        this.maxToolChars = maxToolChars;
        this.summarizer = summarizer;
    }

    public CompressionResult compactIfNeeded(List<ChatMessage> original) {
        int before = estimateTokens(original);
        List<ChatMessage> trimmed = trimToolOutputs(original);
        int threshold = tokenBudget * triggerPercent / 100;
        if (before < threshold) {
            return new CompressionResult(false, before, estimateTokens(trimmed), trimmed);
        }
        int split = splitAtRecentUserTurn(trimmed);
        if (split <= 1) {
            return new CompressionResult(false, before, estimateTokens(trimmed), trimmed);
        }
        List<ChatMessage> prefix = trimmed.subList(1, split);
        String summary = summarizer.summarize(List.copyOf(prefix));
        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(trimmed.get(0));
        compacted.add(ChatMessage.system("Conversation summary (goals, decisions, evidence, unresolved work):\n" + summary));
        compacted.addAll(trimmed.subList(split, trimmed.size()));
        return new CompressionResult(true, before, estimateTokens(compacted), compacted);
    }

    public static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage message : messages) {
            chars += message.role() == null ? 0 : message.role().length();
            chars += message.content() == null ? 0 : message.content().length();
            chars += message.toolCallId() == null ? 0 : message.toolCallId().length();
            for (var call : message.toolCalls()) {
                chars += call.id().length() + call.name().length() + call.arguments().toString().length();
            }
            chars += 12;
        }
        return Math.max(1, (chars + 3) / 4);
    }

    public static String deterministicSummary(List<ChatMessage> messages) {
        StringBuilder summary = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.content() != null && !message.content().isBlank()) {
                String normalized = concise(message.content(), 320);
                String label = message.role().equals("tool")
                        ? "tool-result[" + message.toolCallId() + "]" : message.role();
                summary.append("- ").append(label).append(": ").append(normalized).append('\n');
            }
            for (var call : message.toolCalls()) {
                summary.append("- tool-call ").append(call.name()).append(' ')
                        .append(concise(call.arguments().toString(), 320)).append('\n');
            }
        }
        return summary.toString().stripTrailing();
    }

    private static String concise(String value, int limit) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }

    private List<ChatMessage> trimToolOutputs(List<ChatMessage> messages) {
        List<ChatMessage> trimmed = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (message.role().equals("tool") && message.content() != null && message.content().length() > maxToolChars) {
                String content = message.content().substring(0, maxToolChars) + "\n[tool output compacted]";
                trimmed.add(ChatMessage.tool(message.toolCallId(), content));
            } else {
                trimmed.add(message);
            }
        }
        return trimmed;
    }

    private int splitAtRecentUserTurn(List<ChatMessage> messages) {
        int seen = 0;
        for (int i = messages.size() - 1; i >= 1; i--) {
            if (messages.get(i).role().equals("user") && ++seen == retainedUserTurns) {
                return i;
            }
        }
        return 1;
    }
}
