package dev.youagent.memory;

import dev.youagent.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public final class ConversationBuffer {
    private final List<ChatMessage> messages = new ArrayList<>();

    public synchronized void add(ChatMessage message) {
        messages.add(message);
    }

    public synchronized void replace(List<ChatMessage> replacement) {
        messages.clear();
        messages.addAll(replacement);
    }

    public synchronized List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized void clearExceptSystem() {
        ChatMessage system = messages.stream().filter(message -> message.role().equals("system")).findFirst().orElse(null);
        messages.clear();
        if (system != null) {
            messages.add(system);
        }
    }
}
