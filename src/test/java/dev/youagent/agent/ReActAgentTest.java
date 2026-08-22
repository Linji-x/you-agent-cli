package dev.youagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youagent.llm.ChatMessage;
import dev.youagent.llm.LlmClient;
import dev.youagent.llm.LlmResponse;
import dev.youagent.llm.ToolCall;
import dev.youagent.llm.ToolDefinition;
import dev.youagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActAgentTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void feedsToolResultBackAndCompletes(@TempDir Path workspace) throws Exception {
        ToolCall write = new ToolCall("c1", "write_file", mapper.readTree("""
                {"path":"answer.txt","content":"verified"}
                """));
        QueueClient client = new QueueClient(
                new LlmResponse("", List.of(write), LlmResponse.FinishReason.TOOL_CALLS),
                new LlmResponse("Created and verified answer.txt", List.of(), LlmResponse.FinishReason.STOP)
        );
        ReActAgent agent = new ReActAgent(client,
                ToolRegistry.standard(workspace, Duration.ofSeconds(2)), 5, "test");

        AgentResult result = agent.run("create a file");

        assertEquals(AgentResult.ExitReason.COMPLETED, result.exitReason());
        assertEquals("verified", Files.readString(workspace.resolve("answer.txt")));
        assertTrue(result.history().stream().anyMatch(message -> message.role().equals("tool")
                && message.content().contains("wrote answer.txt")));
        assertEquals(2, result.rounds());
    }

    @Test
    void stopsAtRoundBudget(@TempDir Path workspace) {
        ToolCall list = new ToolCall("c", "list_directory", mapper.createObjectNode().put("path", "."));
        QueueClient client = new QueueClient(
                new LlmResponse("", List.of(list), LlmResponse.FinishReason.TOOL_CALLS),
                new LlmResponse("", List.of(list), LlmResponse.FinishReason.TOOL_CALLS)
        );
        ReActAgent agent = new ReActAgent(client,
                ToolRegistry.standard(workspace, Duration.ofSeconds(2)), 2, "test");

        AgentResult result = agent.run("loop");

        assertEquals(AgentResult.ExitReason.MAX_ROUNDS, result.exitReason());
        assertEquals(2, result.rounds());
    }

    @Test
    void stopsAfterThreeIdenticalFailedCalls(@TempDir Path workspace) {
        ToolCall missing = new ToolCall("c", "missing_tool", mapper.createObjectNode());
        LlmResponse response = new LlmResponse("", List.of(missing), LlmResponse.FinishReason.TOOL_CALLS);
        ReActAgent agent = new ReActAgent(new QueueClient(response, response, response),
                ToolRegistry.standard(workspace, Duration.ofSeconds(2)), 10, "test");

        AgentResult result = agent.run("repeat a broken call");

        assertEquals(AgentResult.ExitReason.REPEATED_FAILURE, result.exitReason());
        assertEquals(3, result.rounds());
    }

    private static final class QueueClient implements LlmClient {
        private final Queue<LlmResponse> responses;

        private QueueClient(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(Arrays.asList(responses));
        }

        @Override
        public LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
            return responses.remove();
        }
    }
}
