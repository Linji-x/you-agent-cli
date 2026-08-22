package dev.youagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youagent.agent.AgentResult;
import dev.youagent.agent.ReActAgent;
import dev.youagent.llm.ChatMessage;
import dev.youagent.llm.LlmClient;
import dev.youagent.llm.LlmResponse;
import dev.youagent.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PlanExecuteAgent {
    private static final String PLANNER_PROMPT = """
            Decompose the objective into a small directed acyclic graph.
            Return JSON only: {"tasks":[{"id":"inspect","description":"...","dependsOn":[],
            "parallelSafe":false,"exclusiveResources":[]}]}
            Use 2-8 tasks. IDs must start with a letter. Dependencies must reference task IDs.
            parallelSafe defaults to false. Set it true only for independent/read-only work.
            Every parallel write must name its target path in exclusiveResources; shared resources serialize.
            Keep tasks independently verifiable and never include markdown fences.
            """;
    private static final String WORKER_PROMPT = """
            You execute one node of a reviewed plan. Use tools for evidence and changes.
            Do only the assigned node, verify it, and return a concise result.
            """;

    private final LlmClient client;
    private final ToolRegistry tools;
    private final int taskMaxRounds;
    private final int maxParallelism;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanExecuteAgent(LlmClient client, ToolRegistry tools, int taskMaxRounds) {
        this(client, tools, taskMaxRounds, 1);
    }

    public PlanExecuteAgent(LlmClient client, ToolRegistry tools, int taskMaxRounds, int maxParallelism) {
        this.client = client;
        this.tools = tools;
        this.taskMaxRounds = taskMaxRounds;
        this.maxParallelism = maxParallelism;
    }

    public PlanRunResult run(String objective) throws IOException {
        LlmResponse planning = client.complete(List.of(
                ChatMessage.system(PLANNER_PROMPT),
                ChatMessage.user(objective)
        ), List.of());
        if (!planning.toolCalls().isEmpty()) {
            throw new IOException("planner returned tool calls instead of a JSON plan");
        }
        List<PlanTask> tasks = parseTasks(planning.content());
        ExecutionPlan plan = new ExecutionPlan(tasks);
        ExecutionReport report = new DagExecutor(maxParallelism).execute(plan, (task, dependencies) -> {
            StringBuilder assignment = new StringBuilder("Overall objective: ").append(objective)
                    .append("\nCurrent node ").append(task.id()).append(": ").append(task.description());
            if (!dependencies.isEmpty()) {
                assignment.append("\nDirect dependency outputs (trusted execution evidence):");
                dependencies.forEach((id, output) -> assignment.append("\n- ").append(id).append(": ").append(output));
            }
            AgentResult result = new ReActAgent(client, tools, taskMaxRounds, WORKER_PROMPT)
                    .run(assignment.toString());
            return result.completed() ? TaskOutcome.success(result.answer())
                    : TaskOutcome.failure(result.exitReason() + ": " + result.answer());
        });
        return new PlanRunResult(tasks, report);
    }

    public List<PlanTask> parseTasks(String raw) throws IOException {
        String json = stripFence(raw == null ? "" : raw.strip());
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException invalid) {
            throw new IOException("planner returned invalid JSON", invalid);
        }
        JsonNode taskNodes = root.path("tasks");
        if (!taskNodes.isArray() || taskNodes.isEmpty() || taskNodes.size() > 8) {
            throw new IOException("planner must return 1-8 tasks");
        }
        List<PlanTask> tasks = new ArrayList<>();
        for (JsonNode node : taskNodes) {
            List<String> dependencies = new ArrayList<>();
            node.path("dependsOn").forEach(value -> dependencies.add(value.asText()));
            List<String> resources = new ArrayList<>();
            node.path("exclusiveResources").forEach(value -> resources.add(value.asText()));
            tasks.add(new PlanTask(node.path("id").asText(), node.path("description").asText(),
                    dependencies, "", mapper.createObjectNode(), node.path("parallelSafe").asBoolean(false), resources));
        }
        new ExecutionPlan(tasks);
        return tasks;
    }

    private static String stripFence(String value) {
        if (!value.startsWith("```")) {
            return value;
        }
        int firstNewline = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        return firstNewline >= 0 && lastFence > firstNewline
                ? value.substring(firstNewline + 1, lastFence).strip()
                : value;
    }
}
