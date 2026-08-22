package dev.youagent.llm;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface LlmClient {
    LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws IOException;
}
