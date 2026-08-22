package dev.youagent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youagent.agent.AgentResult;
import dev.youagent.agent.ReActAgent;
import dev.youagent.llm.ChatMessage;
import dev.youagent.llm.LlmClient;
import dev.youagent.llm.LlmResponse;
import dev.youagent.llm.ToolCall;
import dev.youagent.llm.ToolDefinition;
import dev.youagent.tool.ToolExecution;
import dev.youagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void registersPersistsAndReturnsBoundedCodeEvidence(@TempDir Path workspace) throws Exception {
        writeFixture(workspace);
        Path database = workspace.resolve(".you-agent/index.db");
        try (ToolRegistry tools = ToolRegistry.standard(workspace, Duration.ofSeconds(1), database,
                new FeatureHashEmbeddingModel(128))) {
            assertTrue(tools.names().containsAll(List.of("search_code", "index_codebase",
                    "find_symbol", "find_relations")));
            ToolExecution indexed = tools.execute(call("index_codebase", "{}"));
            ToolExecution searched = tools.execute(call("search_code", "{\"query\":\"authenticate token\",\"limit\":5}"));
            ToolExecution symbol = tools.execute(call("find_symbol", "{\"symbol\":\"AuthService\"}"));
            ToolExecution relations = tools.execute(call("find_relations", "{\"symbol\":\"AuthService\"}"));

            assertTrue(indexed.success());
            assertTrue(searched.output().contains("src/AuthService.java:"));
            assertTrue(searched.output().contains("AuthService#authenticate"));
            assertTrue(searched.output().contains("score="));
            assertTrue(searched.output().length() <= 12_000);
            assertTrue(symbol.output().contains("AuthService"));
            assertTrue(relations.output().contains("CONTAINS"));
        }

        try (ToolRegistry reopened = ToolRegistry.standard(workspace, Duration.ofSeconds(1), database,
                new FeatureHashEmbeddingModel(128))) {
            ToolExecution persisted = reopened.execute(call("search_code", "{\"query\":\"authenticate\"}"));
            assertTrue(persisted.success());
            assertTrue(persisted.output().contains("AuthService#authenticate"));
        }
    }

    @Test
    void reactAgentCanActuallyChooseSearchCode(@TempDir Path workspace) throws Exception {
        writeFixture(workspace);
        ToolCall search = new ToolCall("search-1", "search_code",
                mapper.readTree("{\"query\":\"where is token authentication\",\"limit\":3}"));
        QueueClient client = new QueueClient(
                new LlmResponse("", List.of(search), LlmResponse.FinishReason.TOOL_CALLS),
                new LlmResponse("AuthService#authenticate is the implementation.", List.of(),
                        LlmResponse.FinishReason.STOP));

        try (ToolRegistry tools = ToolRegistry.standard(workspace, Duration.ofSeconds(1))) {
            AgentResult result = new ReActAgent(client, tools, 3, "use tools").run("find token authentication");

            assertEquals(AgentResult.ExitReason.COMPLETED, result.exitReason());
            assertTrue(result.history().stream().filter(message -> message.role().equals("tool"))
                    .anyMatch(message -> message.content().contains("AuthService#authenticate")));
        }
    }

    private ToolCall call(String name, String arguments) throws Exception {
        return new ToolCall("id", name, mapper.readTree(arguments));
    }

    private static void writeFixture(Path workspace) throws IOException {
        Path source = workspace.resolve("src/AuthService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package sample;
                public final class AuthService {
                    public boolean authenticate(String token) {
                        return token != null && token.startsWith("user-");
                    }
                }
                """);
    }

    private static final class QueueClient implements LlmClient {
        private final Queue<LlmResponse> responses;
        private QueueClient(LlmResponse... responses) {
            responses = responses.clone();
            this.responses = new ArrayDeque<>(Arrays.asList(responses));
        }
        @Override public LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
            return responses.remove();
        }
    }
}
