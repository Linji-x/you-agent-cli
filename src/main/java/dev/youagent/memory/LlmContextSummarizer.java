package dev.youagent.memory;

import dev.youagent.llm.ChatMessage;
import dev.youagent.llm.LlmClient;

import java.io.IOException;
import java.util.List;

public final class LlmContextSummarizer implements ContextCompactor.Summarizer {
    private static final String PROMPT = """
            Compress this earlier conversation into a compact handoff.
            Preserve: the user's objective, verified evidence, files changed, decisions and reasons,
            failed approaches, constraints, and unresolved next steps. Omit repetition and chatter.
            Never invent facts. Return plain text only.
            """;
    private final LlmClient client;

    public LlmContextSummarizer(LlmClient client) {
        this.client = client;
    }

    @Override
    public String summarize(List<ChatMessage> messages) {
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.content() != null && !message.content().isBlank()) {
                transcript.append(message.role()).append(": ").append(message.content()).append('\n');
            }
        }
        try {
            String summary = client.complete(List.of(ChatMessage.system(PROMPT), ChatMessage.user(transcript.toString())),
                    List.of()).content();
            return summary.isBlank() ? ContextCompactor.deterministicSummary(messages) : summary;
        } catch (IOException | RuntimeException failure) {
            return ContextCompactor.deterministicSummary(messages);
        }
    }
}
