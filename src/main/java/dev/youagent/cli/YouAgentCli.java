package dev.youagent.cli;

import dev.youagent.agent.AgentEvent;
import dev.youagent.agent.AgentResult;
import dev.youagent.agent.ReActAgent;
import dev.youagent.benchmark.BenchmarkReport;
import dev.youagent.benchmark.BenchmarkRunner;
import dev.youagent.config.AppConfig;
import dev.youagent.config.EmbeddingConfig;
import dev.youagent.demo.DemoRunner;
import dev.youagent.eval.OnlineAgentEvalRunner;
import dev.youagent.eval.OnlineEvaluationReport;
import dev.youagent.eval.RetrievalEvaluationReport;
import dev.youagent.eval.RetrievalEvaluationRunner;
import dev.youagent.llm.OpenAiCompatibleClient;
import dev.youagent.memory.ContextCompactor;
import dev.youagent.memory.LlmContextSummarizer;
import dev.youagent.memory.LongTermMemory;
import dev.youagent.memory.MemoryFact;
import dev.youagent.mcp.McpManager;
import dev.youagent.plan.PlanExecuteAgent;
import dev.youagent.plan.PlanRunResult;
import dev.youagent.search.EmbeddingModels;
import dev.youagent.search.SearchHit;
import dev.youagent.search.SqliteCodeIndex;
import dev.youagent.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class YouAgentCli {
    private static final String SYSTEM_PROMPT = """
            You are You Agent CLI, a workspace-scoped coding agent.
            Inspect evidence before editing. Use registered tools for file or command operations.
            Use search_code, find_symbol, and find_relations when indexed code evidence can locate an implementation.
            Never invent tool results. If a tool fails, change the approach instead of repeating it.
            Finish with a concise result and verification evidence.
            """;

    private YouAgentCli() {
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(args, Path.of("."));
        } catch (Exception failure) {
            System.err.println("ERROR: " + safeMessage(failure));
            exit = 1;
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args, Path workspace) throws Exception {
        if (args.length == 0) {
            return repl(workspace);
        }
        return switch (args[0]) {
            case "--help", "-h" -> {
                printHelp();
                yield 0;
            }
            case "--demo" -> {
                System.out.print(new DemoRunner().run());
                yield 0;
            }
            case "--benchmark" -> runBenchmark(workspace);
            case "--retrieval-eval" -> runRetrievalEvaluation(workspace);
            case "--eval" -> runOnlineEvaluation(workspace);
            case "--once" -> runOnce(workspace, joinTail(args));
            case "--plan" -> runPlan(workspace, joinTail(args));
            case "--index" -> rebuildIndex(workspace);
            case "--search" -> search(workspace, joinTail(args));
            case "--save" -> saveMemory(workspace, joinTail(args));
            case "--memory" -> listMemory(workspace);
            case "--mcp-status" -> mcpStatus(workspace);
            default -> {
                System.err.println("Unknown option: " + args[0]);
                printHelp();
                yield 2;
            }
        };
    }

    private static int repl(Path workspace) throws Exception {
        AppConfig config = AppConfig.load(workspace);
        System.out.println("You Agent CLI 0.2.0-SNAPSHOT | Java 17 | workspace=" + workspace.toAbsolutePath().normalize());
        System.out.println("Type /help or /exit.");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("you> ");
            String line = input.readLine();
            if (line == null || line.strip().equals("/exit")) {
                return 0;
            }
            if (line.isBlank()) {
                continue;
            }
            if (line.strip().equals("/help")) {
                printHelp();
                continue;
            }
            if (line.strip().startsWith("/save ")) {
                saveMemory(workspace, line.strip().substring(6).strip());
                continue;
            }
            if (line.strip().equals("/memory")) {
                listMemory(workspace);
                continue;
            }
            if (line.strip().startsWith("/plan ")) {
                runPlan(workspace, line.strip().substring(6).strip());
                continue;
            }
            if (!config.hasProviderCredentials()) {
                System.out.println("Provider is not configured. Copy .env.example to .env, or run --demo.");
                continue;
            }
            runAgent(config, workspace, line);
        }
    }

    private static int runOnce(Path workspace, String task) throws Exception {
        if (task.isBlank()) {
            System.err.println("--once requires a task");
            return 2;
        }
        AppConfig config = AppConfig.load(workspace);
        if (!config.hasProviderCredentials()) {
            System.err.println("Provider is not configured. Copy .env.example to .env.");
            return 2;
        }
        return runAgent(config, workspace, task);
    }

    private static int runPlan(Path workspace, String task) throws Exception {
        if (task.isBlank()) {
            System.err.println("--plan requires a task");
            return 2;
        }
        AppConfig config = AppConfig.load(workspace);
        if (!config.hasProviderCredentials()) {
            System.err.println("Provider is not configured. Copy .env.example to .env.");
            return 2;
        }
        PlanRunResult result;
        try (ToolRegistry tools = tools(config, workspace);
             McpManager mcp = new McpManager(workspace, tools)) {
            mcp.start();
            result = new PlanExecuteAgent(new OpenAiCompatibleClient(config), tools,
                    config.maxRounds(), config.planMaxParallelism()).run(task);
        }
        System.out.println("PLAN");
        result.tasks().forEach(planTask -> System.out.println("  " + planTask.id() + " <- "
                + planTask.dependsOn() + " : " + planTask.description()));
        System.out.println("EXECUTION");
        result.tasks().forEach(planTask -> System.out.println("  " + planTask.id() + " -> "
                + result.report().statuses().get(planTask.id()) + " : "
                + oneLine(result.report().outputs().getOrDefault(planTask.id(), "blocked"))));
        return result.report().succeeded() ? 0 : 1;
    }

    private static int runBenchmark(Path workspace) throws Exception {
        BenchmarkReport report = new BenchmarkRunner().run(workspace.toAbsolutePath().normalize());
        System.out.println("Deterministic offline conformance benchmark: "
                + report.passed() + "/" + report.total() + " passed");
        System.out.println("Report: benchmarks/results/latest.md");
        return report.passed() == report.total() ? 0 : 1;
    }

    private static int runRetrievalEvaluation(Path workspace) throws Exception {
        RetrievalEvaluationReport report = new RetrievalEvaluationRunner().run(
                workspace.toAbsolutePath().normalize(), EmbeddingConfig.load(workspace));
        for (var configuration : report.configurations()) {
            System.out.printf("%s Recall@5=%.3f MRR@10=%.3f latency=%.3fms%n", configuration.name(),
                    configuration.recallAt5(), configuration.mrrAt10(), configuration.averageLatencyMs());
        }
        System.out.println("Report: eval/results/retrieval-latest.md");
        return 0;
    }

    private static int runOnlineEvaluation(Path workspace) throws Exception {
        AppConfig config = AppConfig.load(workspace);
        if (!config.hasProviderCredentials()) {
            System.err.println("--eval requires explicit YOU_AGENT_API_KEY, YOU_AGENT_BASE_URL, and YOU_AGENT_MODEL");
            return 2;
        }
        OnlineEvaluationReport report = new OnlineAgentEvalRunner().run(
                workspace.toAbsolutePath().normalize(), config);
        System.out.printf("Online evaluation: %d/%d passed (%.1f%%)%n", report.passed(), report.total(),
                report.completionRate() * 100);
        System.out.println("Report: eval/results/online-latest.md");
        return report.passed() == report.total() ? 0 : 1;
    }

    private static int runAgent(AppConfig config, Path workspace, String task) throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(config);
        ContextCompactor compactor = new ContextCompactor(config.contextBudgetTokens(),
                config.compressionTriggerPercent(), 2, 8_000, new LlmContextSummarizer(client));
        AgentResult result;
        try (ToolRegistry tools = tools(config, workspace);
             McpManager mcp = new McpManager(workspace, tools)) {
            mcp.start();
            ReActAgent agent = new ReActAgent(client, tools, config.maxRounds(),
                    SYSTEM_PROMPT + relevantMemory(config, workspace, task), () -> false, compactor);
            result = agent.run(task);
        }
        for (AgentEvent event : result.events()) {
            if (event.type() == AgentEvent.Type.TOOL_CALL || event.type() == AgentEvent.Type.TOOL_RESULT) {
                System.out.printf("[%s] %s %s%n", event.type(), event.name(), oneLine(event.detail()));
            }
        }
        System.out.println(result.answer());
        return result.completed() ? 0 : 1;
    }

    private static int mcpStatus(Path workspace) throws Exception {
        AppConfig config = AppConfig.load(workspace);
        try (ToolRegistry tools = tools(config, workspace);
             McpManager manager = new McpManager(workspace, tools)) {
            manager.start();
            if (manager.statuses().isEmpty()) {
                System.out.println("No MCP servers configured at " + manager.configFile());
            } else {
                manager.statuses().forEach(status -> System.out.printf("%s %s tools=%d %s%n",
                        status.name(), status.lifecycle(), status.toolCount(), status.detail()));
            }
        }
        return 0;
    }

    private static int saveMemory(Path workspace, String fact) throws Exception {
        if (fact.isBlank()) {
            System.err.println("--save requires a stable fact");
            return 2;
        }
        AppConfig config = AppConfig.load(workspace);
        MemoryFact saved = new LongTermMemory(config.memoryFile()).save(projectScope(workspace), fact);
        System.out.println("Saved memory " + saved.id());
        return 0;
    }

    private static int listMemory(Path workspace) throws Exception {
        AppConfig config = AppConfig.load(workspace);
        List<MemoryFact> facts = new LongTermMemory(config.memoryFile()).listVisible(projectScope(workspace));
        if (facts.isEmpty()) {
            System.out.println("No visible memories.");
        } else {
            facts.forEach(fact -> System.out.println(fact.id() + " [" + fact.scope() + "] " + fact.content()));
        }
        return 0;
    }

    private static String relevantMemory(AppConfig config, Path workspace, String task) {
        try {
            List<MemoryFact> facts = new LongTermMemory(config.memoryFile()).search(projectScope(workspace), task, 5);
            if (facts.isEmpty()) {
                return "";
            }
            StringBuilder context = new StringBuilder("\nRelevant explicit long-term memory:\n");
            facts.forEach(fact -> context.append("- ").append(fact.content()).append('\n'));
            return context.toString();
        } catch (IOException failure) {
            return "";
        }
    }

    private static String projectScope(Path workspace) {
        return "project:" + workspace.toAbsolutePath().normalize().toString().toLowerCase();
    }

    private static int rebuildIndex(Path workspace) throws Exception {
        AppConfig config = AppConfig.load(workspace);
        try (SqliteCodeIndex index = new SqliteCodeIndex(workspace, config.indexFile(),
                EmbeddingModels.from(EmbeddingConfig.load(workspace)))) {
            int chunks = index.rebuild();
            System.out.println("Indexed " + chunks + " Java chunks into " + config.indexFile());
        }
        return 0;
    }

    private static int search(Path workspace, String query) throws Exception {
        if (query.isBlank()) {
            System.err.println("--search requires a query");
            return 2;
        }
        AppConfig config = AppConfig.load(workspace);
        try (SqliteCodeIndex index = new SqliteCodeIndex(workspace, config.indexFile(),
                EmbeddingModels.from(EmbeddingConfig.load(workspace)))) {
            for (SearchHit hit : index.search(query, 10)) {
                System.out.printf("%.3f %s:%d %s [%s]%n", hit.score(), hit.chunk().path(),
                        hit.chunk().startLine(), hit.chunk().symbol(), hit.chunk().type());
            }
        }
        return 0;
    }

    private static String joinTail(String[] args) {
        return String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
    }

    private static ToolRegistry tools(AppConfig config, Path workspace) throws IOException {
        return ToolRegistry.standard(workspace, config.commandTimeout(), config.indexFile(),
                EmbeddingModels.from(EmbeddingConfig.load(workspace)));
    }

    private static String oneLine(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "…";
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void printHelp() {
        System.out.println("""
                You Agent CLI

                One-command start:
                  Windows: .\\run.ps1
                  macOS/Linux: ./run.sh

                Commands:
                  --demo                 offline input -> plan -> tools -> result demo
                  --benchmark            run the 25-task deterministic offline conformance benchmark
                  --retrieval-eval       run the labeled code-retrieval evaluation
                  --eval                 run real model-backed agent tasks (credentials required)
                  --once <task>          run one ReAct task with configured provider
                  --plan <task>          generate a DAG and execute its nodes with ReAct
                  --index                rebuild the local Java AST/SQLite index
                  --search <query>       query the hybrid code index
                  --save <fact>          explicitly persist a project-scoped fact
                  --memory               list project-visible long-term memory
                  --mcp-status           initialize configured MCP servers and show status
                  --help                 show this help
                """);
    }
}
