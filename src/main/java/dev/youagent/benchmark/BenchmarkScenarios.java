package dev.youagent.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.agent.AgentResult;
import dev.youagent.agent.ReActAgent;
import dev.youagent.llm.ChatMessage;
import dev.youagent.llm.LlmClient;
import dev.youagent.llm.LlmResponse;
import dev.youagent.llm.ToolCall;
import dev.youagent.llm.ToolDefinition;
import dev.youagent.mcp.McpClient;
import dev.youagent.mcp.McpLifecycle;
import dev.youagent.mcp.McpRemoteTool;
import dev.youagent.mcp.McpToolAdapter;
import dev.youagent.mcp.McpTransport;
import dev.youagent.memory.ContextCompactor;
import dev.youagent.memory.LongTermMemory;
import dev.youagent.memory.MemoryFact;
import dev.youagent.plan.DagExecutor;
import dev.youagent.plan.ExecutionPlan;
import dev.youagent.plan.ExecutionReport;
import dev.youagent.plan.PlanTask;
import dev.youagent.plan.TaskOutcome;
import dev.youagent.plan.TaskStatus;
import dev.youagent.search.ChunkType;
import dev.youagent.search.CodeRelation;
import dev.youagent.search.HashEmbeddingModel;
import dev.youagent.search.SqliteCodeIndex;
import dev.youagent.tool.ToolExecution;
import dev.youagent.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;

final class BenchmarkScenarios {
    private final ObjectMapper mapper = new ObjectMapper();

    String run(String id) throws Exception {
        Path workspace = Files.createTempDirectory("you-agent-benchmark-");
        try {
            return switch (id) {
                case "R01" -> reactToolFeedback(workspace);
                case "R02" -> reactRoundLimit(workspace);
                case "R03" -> reactRepeatedFailure(workspace);
                case "R04" -> reactDirectFinish(workspace);
                case "R05" -> reactInvalidArgumentsRecovery(workspace);
                case "D01" -> dagOrder();
                case "D02" -> dagFailurePropagation();
                case "D03" -> dagCycleRejected();
                case "D04" -> dagSupplement();
                case "D05" -> dagIndependentBranches();
                case "M01" -> memoryPersistence(workspace);
                case "M02" -> memoryScopes(workspace);
                case "M03" -> memoryRetrieval(workspace);
                case "M04" -> memoryDelete(workspace);
                case "M05" -> memoryCompression();
                case "S01" -> searchChunks(workspace);
                case "S02" -> searchRanking(workspace);
                case "S03" -> searchRelations(workspace);
                case "S04" -> searchPersistence(workspace);
                case "S05" -> searchSymbol(workspace);
                case "T01" -> toolPathPolicy(workspace);
                case "T02" -> toolSchema(workspace);
                case "T03" -> toolRoundTrip(workspace);
                case "P01" -> mcpLifecycle();
                case "P02" -> mcpDynamicTool(workspace);
                default -> throw new IllegalArgumentException("unknown benchmark id " + id);
            };
        } finally {
            deleteTree(workspace);
        }
    }

    private String reactToolFeedback(Path workspace) throws Exception {
        ToolCall call = new ToolCall("c1", "write_file", mapper.createObjectNode()
                .put("path", "result.txt").put("content", "ok"));
        ReActAgent agent = agent(workspace, 5,
                new LlmResponse("", List.of(call), LlmResponse.FinishReason.TOOL_CALLS),
                new LlmResponse("done", List.of(), LlmResponse.FinishReason.STOP));
        AgentResult result = agent.run("write result");
        require(result.completed() && Files.readString(workspace.resolve("result.txt")).equals("ok"), "write or finish failed");
        require(result.history().stream().anyMatch(message -> message.role().equals("tool")), "tool result missing from history");
        return "COMPLETED in 2 rounds; tool result present";
    }

    private String reactRoundLimit(Path workspace) {
        ToolCall call = new ToolCall("c", "list_directory", mapper.createObjectNode().put("path", "."));
        ReActAgent agent = agent(workspace, 2,
                new LlmResponse("", List.of(call), LlmResponse.FinishReason.TOOL_CALLS),
                new LlmResponse("", List.of(call), LlmResponse.FinishReason.TOOL_CALLS));
        AgentResult result = agent.run("keep going");
        require(result.exitReason() == AgentResult.ExitReason.MAX_ROUNDS && result.rounds() == 2, "round limit not enforced");
        return "MAX_ROUNDS at round 2";
    }

    private String reactRepeatedFailure(Path workspace) {
        ToolCall call = new ToolCall("c", "unknown_tool", mapper.createObjectNode());
        LlmResponse response = new LlmResponse("", List.of(call), LlmResponse.FinishReason.TOOL_CALLS);
        AgentResult result = agent(workspace, 9, response, response, response).run("repeat failure");
        require(result.exitReason() == AgentResult.ExitReason.REPEATED_FAILURE, "repetition guard did not fire");
        return "REPEATED_FAILURE after 3 identical failures";
    }

    private String reactDirectFinish(Path workspace) {
        AgentResult result = agent(workspace, 3,
                new LlmResponse("final answer", List.of(), LlmResponse.FinishReason.STOP)).run("answer");
        require(result.completed() && result.rounds() == 1, "direct finish failed");
        return "COMPLETED in 1 round with no tools";
    }

    private String reactInvalidArgumentsRecovery(Path workspace) {
        ToolCall invalid = new ToolCall("c1", "write_file", mapper.createObjectNode().put("path", "a.txt"));
        AgentResult result = agent(workspace, 3,
                new LlmResponse("", List.of(invalid), LlmResponse.FinishReason.TOOL_CALLS),
                new LlmResponse("recovered", List.of(), LlmResponse.FinishReason.STOP)).run("recover");
        require(result.completed(), "agent did not recover");
        require(result.history().stream().anyMatch(message -> message.role().equals("tool")
                && message.content().contains("INVALID_ARGUMENTS")), "structured error not observed");
        return "INVALID_ARGUMENTS fed back; next round COMPLETED";
    }

    private String dagOrder() {
        ExecutionPlan plan = new ExecutionPlan(List.of(task("verify", List.of("generate")),
                task("inspect", List.of()), task("generate", List.of("inspect"))));
        List<String> order = new ArrayList<>();
        ExecutionReport report = new DagExecutor().execute(plan, task -> {
            order.add(task.id());
            return TaskOutcome.success("ok");
        });
        require(report.succeeded() && order.equals(List.of("inspect", "generate", "verify")), "wrong order " + order);
        return String.join(" -> ", order);
    }

    private String dagFailurePropagation() {
        ExecutionPlan plan = new ExecutionPlan(List.of(task("root", List.of()), task("child", List.of("root"))));
        ExecutionReport report = new DagExecutor().execute(plan,
                task -> task.id().equals("root") ? TaskOutcome.failure("boom") : TaskOutcome.success("unexpected"));
        require(report.statuses().get("root") == TaskStatus.FAILED
                && report.statuses().get("child") == TaskStatus.BLOCKED, "failure not propagated");
        return "root=FAILED; child=BLOCKED";
    }

    private String dagCycleRejected() {
        try {
            new ExecutionPlan(List.of(task("a", List.of("b")), task("b", List.of("a"))));
            throw new AssertionError("cycle accepted");
        } catch (IllegalArgumentException expected) {
            return "cycle rejected: " + expected.getMessage();
        }
    }

    private String dagSupplement() {
        ExecutionPlan plan = new ExecutionPlan(List.of(task("inspect", List.of())));
        plan.append(List.of(task("fix", List.of("inspect"))));
        ExecutionReport report = new DagExecutor().execute(plan, task -> TaskOutcome.success(task.id()));
        require(report.succeeded() && report.statuses().size() == 2, "supplement failed");
        return "supplemental fix task SUCCEEDED";
    }

    private String dagIndependentBranches() {
        ExecutionPlan plan = new ExecutionPlan(List.of(task("left", List.of()), task("right", List.of())));
        List<String> executed = new ArrayList<>();
        ExecutionReport report = new DagExecutor().execute(plan, task -> {
            executed.add(task.id());
            return TaskOutcome.success("ok");
        });
        require(report.succeeded() && executed.size() == 2, "independent branch missing");
        return "left and right SUCCEEDED";
    }

    private String memoryPersistence(Path workspace) throws Exception {
        Path file = workspace.resolve("memory.jsonl");
        new LongTermMemory(file).save("project", "Persist this stable fact");
        int visible = new LongTermMemory(file).listVisible("project").size();
        require(visible == 1, "fact did not persist");
        return "1 fact visible after reopen";
    }

    private String memoryScopes(Path workspace) throws Exception {
        LongTermMemory memory = new LongTermMemory(workspace.resolve("memory.jsonl"));
        memory.save("global", "global fact");
        memory.save("alpha", "alpha fact");
        memory.save("beta", "beta fact");
        List<MemoryFact> alpha = memory.listVisible("alpha");
        require(alpha.size() == 2 && alpha.stream().noneMatch(fact -> fact.content().equals("beta fact")), "scope leaked");
        return "alpha sees global+alpha only";
    }

    private String memoryRetrieval(Path workspace) throws Exception {
        LongTermMemory memory = new LongTermMemory(workspace.resolve("memory.jsonl"));
        memory.save("project", "Use SQLite for persistent code metadata");
        memory.save("project", "Use blue for terminal headings");
        String first = memory.search("project", "SQLite metadata", 2).get(0).content();
        require(first.contains("SQLite"), "wrong fact ranked first");
        return "SQLite fact ranked first";
    }

    private String memoryDelete(Path workspace) throws Exception {
        LongTermMemory memory = new LongTermMemory(workspace.resolve("memory.jsonl"));
        MemoryFact fact = memory.save("project", "delete me");
        require(memory.delete(fact.id()) && memory.listVisible("project").isEmpty(), "delete failed");
        return "fact deleted by id";
    }

    private String memoryCompression() {
        List<ChatMessage> messages = List.of(ChatMessage.system("system"), ChatMessage.user("goal"),
                ChatMessage.tool("c", "x".repeat(2_000)), ChatMessage.assistant("decision", List.of()),
                ChatMessage.user("recent task"), ChatMessage.assistant("recent result", List.of()));
        var result = new ContextCompactor(200, 50, 1, 100, ContextCompactor::deterministicSummary)
                .compactIfNeeded(messages);
        require(result.compressed() && result.tokensAfter() < result.tokensBefore(), "tokens not reduced");
        require(result.messages().stream().anyMatch(message -> "recent task".equals(message.content())), "recent turn lost");
        return result.tokensBefore() + " -> " + result.tokensAfter() + " estimated tokens";
    }

    private String searchChunks(Path workspace) throws Exception {
        writeSearchFixture(workspace);
        try (SqliteCodeIndex index = index(workspace)) {
            int chunks = index.rebuild();
            var hits = index.search("AccountService", 20);
            require(chunks >= 4, "too few chunks");
            require(hits.stream().map(hit -> hit.chunk().type()).distinct().count() >= 2, "chunk types missing");
            return chunks + " AST chunks indexed";
        }
    }

    private String searchRanking(Path workspace) throws Exception {
        writeSearchFixture(workspace);
        try (SqliteCodeIndex index = index(workspace)) {
            index.rebuild();
            var first = index.search("load account customer id", 5).get(0).chunk();
            require(first.type() == ChunkType.METHOD && first.symbol().contains("loadAccount"), "wrong top hit " + first.symbol());
            return first.symbol() + " ranked first";
        }
    }

    private String searchRelations(Path workspace) throws Exception {
        writeSearchFixture(workspace);
        try (SqliteCodeIndex index = index(workspace)) {
            index.rebuild();
            boolean contains = index.relationsFor("AccountService").stream()
                    .anyMatch(relation -> relation.type() == CodeRelation.RelationType.CONTAINS);
            require(contains, "CONTAINS relation missing");
            return "AccountService CONTAINS method relation stored";
        }
    }

    private String searchPersistence(Path workspace) throws Exception {
        writeSearchFixture(workspace);
        try (SqliteCodeIndex index = index(workspace)) {
            index.rebuild();
        }
        try (SqliteCodeIndex reopened = index(workspace)) {
            require(!reopened.search("loadAccount", 3).isEmpty(), "reopened index empty");
        }
        return "search succeeded after SQLite reopen";
    }

    private String searchSymbol(Path workspace) throws Exception {
        writeSearchFixture(workspace);
        try (SqliteCodeIndex index = index(workspace)) {
            index.rebuild();
            var hits = index.search("AccountService", 3);
            require(hits.stream().anyMatch(hit -> hit.chunk().symbol().contains("AccountService")), "symbol result absent");
            return "AccountService symbol returned in top 3";
        }
    }

    private String toolPathPolicy(Path workspace) {
        ToolExecution result = tools(workspace).execute(new ToolCall("c", "write_file", mapper.createObjectNode()
                .put("path", "../escape.txt").put("content", "denied")));
        require(!result.success() && result.errorCode().equals("POLICY_DENIED"), "path escape allowed");
        return "POLICY_DENIED";
    }

    private String toolSchema(Path workspace) {
        ToolExecution result = tools(workspace).execute(new ToolCall("c", "read_file", mapper.createObjectNode()));
        require(!result.success() && result.errorCode().equals("INVALID_ARGUMENTS"), "schema accepted missing path");
        return "INVALID_ARGUMENTS: missing path";
    }

    private String toolRoundTrip(Path workspace) {
        ToolRegistry tools = tools(workspace);
        ToolExecution write = tools.execute(new ToolCall("w", "write_file", mapper.createObjectNode()
                .put("path", "round-trip.txt").put("content", "round-trip-ok")));
        ToolExecution read = tools.execute(new ToolCall("r", "read_file", mapper.createObjectNode()
                .put("path", "round-trip.txt")));
        require(write.success() && read.success() && read.output().equals("round-trip-ok"), "round trip mismatch");
        return "round-trip-ok";
    }

    private String mcpLifecycle() throws Exception {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpClient client = new McpClient("demo", transport);
        client.start(Duration.ofSeconds(1));
        require(client.lifecycle() == McpLifecycle.READY && transport.initialized, "client not ready");
        client.close();
        require(client.lifecycle() == McpLifecycle.CLOSED, "client not closed");
        return "initialize -> initialized -> READY -> CLOSED";
    }

    private String mcpDynamicTool(Path workspace) throws Exception {
        FakeMcpTransport transport = new FakeMcpTransport();
        try (McpClient client = new McpClient("demo", transport)) {
            client.start(Duration.ofSeconds(1));
            McpRemoteTool remote = client.listTools(Duration.ofSeconds(1)).get(0);
            ToolRegistry registry = tools(workspace);
            registry.register(new McpToolAdapter(client, remote, Duration.ofSeconds(1)));
            ToolExecution execution = registry.execute(new ToolCall("m", "mcp__demo__echo",
                    mapper.createObjectNode().put("text", "hello-mcp")));
            require(execution.success() && execution.output().equals("hello-mcp"), "dynamic MCP tool failed");
            return "mcp__demo__echo returned hello-mcp";
        }
    }

    private ReActAgent agent(Path workspace, int rounds, LlmResponse... responses) {
        return new ReActAgent(new QueueClient(responses), tools(workspace), rounds, "benchmark");
    }

    private ToolRegistry tools(Path workspace) {
        return ToolRegistry.standard(workspace, Duration.ofSeconds(2));
    }

    private PlanTask task(String id, List<String> dependencies) {
        return new PlanTask(id, id, dependencies, "", null);
    }

    private SqliteCodeIndex index(Path workspace) throws Exception {
        return new SqliteCodeIndex(workspace, workspace.resolve(".data/index.db"), new HashEmbeddingModel(128));
    }

    private void writeSearchFixture(Path workspace) throws IOException {
        Path file = workspace.resolve("src/AccountService.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package benchmark;
                import java.util.Objects;
                public final class AccountService implements AutoCloseable {
                    public String loadAccount(String customerId) {
                        return Objects.requireNonNull(customerId);
                    }
                    public void close() { }
                }
                """);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
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

    private static final class FakeMcpTransport implements McpTransport {
        private final ObjectMapper mapper = new ObjectMapper();
        private boolean started;
        private boolean initialized;

        @Override public void start(Duration timeout) { started = true; }

        @Override
        public JsonNode request(ObjectNode message, Duration timeout) {
            require(started, "transport not started");
            ObjectNode response = mapper.createObjectNode();
            response.set("id", message.path("id"));
            ObjectNode result = response.putObject("result");
            switch (message.path("method").asText()) {
                case "initialize" -> result.put("protocolVersion", McpClient.PROTOCOL_VERSION);
                case "tools/list" -> {
                    ObjectNode tool = result.putArray("tools").addObject();
                    tool.put("name", "echo");
                    tool.put("description", "Echo input text");
                    ObjectNode schema = tool.putObject("inputSchema");
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("text").put("type", "string");
                    schema.putArray("required").add("text");
                    schema.put("additionalProperties", false);
                }
                case "tools/call" -> result.putArray("content").addObject().put("type", "text")
                        .put("text", message.path("params").path("arguments").path("text").asText());
                default -> throw new AssertionError("unexpected MCP method");
            }
            return response;
        }

        @Override public void notify(ObjectNode message) {
            initialized = message.path("method").asText().equals("notifications/initialized");
        }
        @Override public void close() { started = false; }
    }
}
