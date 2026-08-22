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
import java.util.ArrayList;

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

    @Test
    void injectsUpstreamOutputIntoDownstreamWorkerContext(@TempDir Path workspace) throws Exception {
        QueueClient client = new QueueClient(
                new LlmResponse("""
                        {"tasks":[
                          {"id":"discover","description":"discover","dependsOn":[],"parallelSafe":false},
                          {"id":"use","description":"use evidence","dependsOn":["discover"],"parallelSafe":false}
                        ]}
                        """, List.of(), LlmResponse.FinishReason.STOP),
                new LlmResponse("UPSTREAM_EVIDENCE", List.of(), LlmResponse.FinishReason.STOP),
                new LlmResponse("used it", List.of(), LlmResponse.FinishReason.STOP)
        );

        PlanRunResult result = new PlanExecuteAgent(client,
                ToolRegistry.standard(workspace, Duration.ofSeconds(1)), 2, 4).run("discover then use");

        assertTrue(result.report().succeeded());
        String downstreamAssignment = client.requests.get(2).stream()
                .filter(message -> message.role().equals("user"))
                .findFirst().orElseThrow().content();
        assertTrue(downstreamAssignment.contains("discover: UPSTREAM_EVIDENCE"));
        assertEquals(false, result.tasks().get(0).parallelSafe());
    }

    private static final class QueueClient implements LlmClient {
        private final Queue<LlmResponse> responses;
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private QueueClient(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(Arrays.asList(responses));
        }
        @Override public LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) throws IOException {
            requests.add(List.copyOf(messages));
            return responses.remove();
        }
    }
}
