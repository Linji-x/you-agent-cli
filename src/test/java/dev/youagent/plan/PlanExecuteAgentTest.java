package dev.youagent.plan;

import dev.youagent.llm.ChatMessage;
import dev.youagent.llm.LlmClient;
import dev.youagent.llm.LlmResponse;
import dev.youagent.llm.ToolDefinition;
import dev.youagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {
    @Test
    void generatesValidatesAndExecutesPlan(@TempDir Path workspace) throws Exception {
        QueueClient client = new QueueClient(
                new LlmResponse("""
                        {"tasks":[
                          {"id":"inspect","description":"inspect","dependsOn":[]},
                          {"id":"verify","description":"verify","dependsOn":["inspect"]}
                        ]}
                        """, List.of(), LlmResponse.FinishReason.STOP),
                new LlmResponse("inspection complete", List.of(), LlmResponse.FinishReason.STOP),
                new LlmResponse("verification complete", List.of(), LlmResponse.FinishReason.STOP)
        );
        PlanExecuteAgent agent = new PlanExecuteAgent(client,
                ToolRegistry.standard(workspace, Duration.ofSeconds(1)), 3);

        PlanRunResult result = agent.run("inspect then verify");

        assertTrue(result.report().succeeded());
        assertEquals(List.of("inspect", "verify"), result.tasks().stream().map(PlanTask::id).toList());
    }

    @Test
    void rejectsCyclicPlannerOutput(@TempDir Path workspace) {
        QueueClient client = new QueueClient(new LlmResponse("""
                {"tasks":[
                  {"id":"a","description":"a","dependsOn":["b"]},
                  {"id":"b","description":"b","dependsOn":["a"]}
                ]}
                """, List.of(), LlmResponse.FinishReason.STOP));
        PlanExecuteAgent agent = new PlanExecuteAgent(client,
                ToolRegistry.standard(workspace, Duration.ofSeconds(1)), 2);

        assertThrows(IllegalArgumentException.class, () -> agent.run("bad plan"));
    }

    private static final class QueueClient implements LlmClient {
        private final Queue<LlmResponse> responses;
        private QueueClient(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(Arrays.asList(responses));
        }
        @Override public LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws IOException {
            return responses.remove();
        }
    }
}
