package dev.youagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.youagent.agent.AgentEvent;
import dev.youagent.agent.AgentResult;
import dev.youagent.agent.ReActAgent;
import dev.youagent.config.AppConfig;
import dev.youagent.config.EmbeddingConfig;
import dev.youagent.llm.OpenAiCompatibleClient;
import dev.youagent.memory.ContextCompactor;
import dev.youagent.plan.PlanExecuteAgent;
import dev.youagent.plan.PlanRunResult;
import dev.youagent.search.EmbeddingModels;
import dev.youagent.tool.ToolRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class OnlineAgentEvalRunner {
    private static final String SYSTEM_PROMPT = """
            You are running a measured coding-agent evaluation in a temporary workspace.
            Use tools, inspect evidence, make the requested change, and verify deterministically.
            Never claim success unless the requested artifact and verification are real.
            """;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public OnlineEvaluationReport run(Path projectRoot, AppConfig config) throws Exception {
        if (!config.hasProviderCredentials()) {
            throw new IllegalStateException("--eval requires explicit YOU_AGENT_API_KEY, YOU_AGENT_BASE_URL, and YOU_AGENT_MODEL");
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        List<OnlineEvalTask> tasks = mapper.readValue(root.resolve("eval/online/tasks.json").toFile(),
                new TypeReference<>() { });
        List<OnlineEvaluationReport.CaseResult> results = new ArrayList<>();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(config);
        EmbeddingConfig embeddingConfig = EmbeddingConfig.load(root);
        for (OnlineEvalTask task : tasks) {
            Path workspace = Files.createTempDirectory("you-agent-online-eval-" + task.id().toLowerCase() + "-");
            long started = System.nanoTime();
            try {
                prepare(task.id(), workspace);
                OnlineEvaluationReport.CaseResult caseResult;
                try (ToolRegistry tools = ToolRegistry.standard(workspace, config.commandTimeout(),
                        workspace.resolve(".you-agent/code-index.db"), EmbeddingModels.from(embeddingConfig))) {
                    caseResult = task.planMode()
                            ? runPlan(task, workspace, client, tools, config, started)
                            : runReAct(task, workspace, client, tools, config, started);
                }
                results.add(caseResult);
            } catch (Exception failure) {
                results.add(new OnlineEvaluationReport.CaseResult(task.id(), task.category(), false,
                        0, 0, elapsedMs(started), 0, "", safeMessage(failure)));
            } finally {
                deleteTree(workspace);
            }
        }
        int passed = (int) results.stream().filter(OnlineEvaluationReport.CaseResult::passed).count();
        OnlineEvaluationReport report = new OnlineEvaluationReport(Instant.now(), gitCommit(root),
                System.getProperty("java.version"), System.getProperty("os.name") + " "
                + System.getProperty("os.arch"), config.model(), results.size(), passed,
                results.isEmpty() ? 0 : passed / (double) results.size(), results);
        write(root, report);
        return report;
    }

    private OnlineEvaluationReport.CaseResult runReAct(OnlineEvalTask task, Path workspace,
            OpenAiCompatibleClient client, ToolRegistry tools, AppConfig config, long started) throws IOException {
        AgentResult result = new ReActAgent(client, tools, config.maxRounds(), SYSTEM_PROMPT).run(task.prompt());
        Verification verification = verify(task.id(), workspace, result.events());
        boolean passed = result.completed() && verification.passed();
        int toolCalls = (int) result.events().stream()
                .filter(event -> event.type() == AgentEvent.Type.TOOL_CALL).count();
        return new OnlineEvaluationReport.CaseResult(task.id(), task.category(), passed, result.rounds(),
                toolCalls, elapsedMs(started), ContextCompactor.estimateTokens(result.history()),
                verification.detail(), passed ? "" : result.exitReason() + ": " + result.answer());
    }

    private OnlineEvaluationReport.CaseResult runPlan(OnlineEvalTask task, Path workspace,
            OpenAiCompatibleClient client, ToolRegistry tools, AppConfig config, long started) throws IOException {
        PlanRunResult result = new PlanExecuteAgent(client, tools, config.maxRounds(),
                config.planMaxParallelism()).run(task.prompt());
        Verification verification = verify(task.id(), workspace, result.events());
        boolean passed = result.report().succeeded() && verification.passed();
        int toolCalls = (int) result.events().stream()
                .filter(event -> event.type() == AgentEvent.Type.TOOL_CALL).count();
        return new OnlineEvaluationReport.CaseResult(task.id(), task.category(), passed, result.rounds(),
                toolCalls, elapsedMs(started), result.estimatedTokens(), verification.detail(),
                passed ? "" : "plan execution or deterministic verifier failed");
    }

    private static void prepare(String id, Path workspace) throws IOException {
        switch (id) {
            case "E01" -> write(workspace, "src/AuthService.java", """
                    public final class AuthService {
                        public boolean authenticateToken(String token) {
                            return token != null && token.startsWith("usr-");
                        }
                    }
                    """);
            case "E02" -> write(workspace, "message.txt", "status=old\n");
            case "E03" -> write(workspace, "CommandFixture.java", """
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    public final class CommandFixture {
                        public static void main(String[] args) throws Exception {
                            Files.writeString(Path.of("command.ok"), "command verified");
                        }
                    }
                    """);
            case "E04" -> write(workspace, "Broken.java", """
                    public final class Broken {
                        public static void main(String[] args) {
                            System.out.println(MISSING_SYMBOL);
                        }
                    }
                    """);
            case "E05" -> write(workspace, "README.txt", "plan evaluation workspace\n");
            default -> throw new IOException("unknown online evaluation task: " + id);
        }
    }

    private static Verification verify(String id, Path workspace, List<AgentEvent> events) throws IOException {
        return switch (id) {
            case "E01" -> {
                boolean artifact = contains(workspace.resolve("eval-locate.txt"), "AuthService#authenticateToken");
                boolean searched = called(events, "search_code") || called(events, "find_symbol");
                yield new Verification(artifact && searched, "symbol artifact=" + artifact + ", search tool=" + searched);
            }
            case "E02" -> new Verification(contains(workspace.resolve("message.txt"), "status=ready"),
                    "message.txt contains status=ready");
            case "E03" -> {
                boolean artifact = contains(workspace.resolve("command.ok"), "command verified");
                boolean command = called(events, "execute_command");
                yield new Verification(artifact && command, "command.ok=" + artifact + ", command tool=" + command);
            }
            case "E04" -> {
                boolean artifact = contains(workspace.resolve("recovery.ok"), "recovered");
                boolean failed = events.stream().anyMatch(event -> event.type() == AgentEvent.Type.TOOL_RESULT
                        && !event.success());
                boolean succeeded = events.stream().anyMatch(event -> event.type() == AgentEvent.Type.TOOL_RESULT
                        && event.success() && event.name().equals("execute_command"));
                yield new Verification(artifact && failed && succeeded,
                        "recovery.ok=" + artifact + ", observed failure=" + failed + ", successful command=" + succeeded);
            }
            case "E05" -> new Verification(contains(workspace.resolve("plan-result.txt"), "planned"),
                    "plan-result.txt contains planned");
            default -> new Verification(false, "unknown verifier");
        };
    }

    private void write(Path root, OnlineEvaluationReport report) throws IOException {
        Path results = root.resolve("eval/results");
        Files.createDirectories(results);
        mapper.writeValue(results.resolve("online-latest.json").toFile(), report);
        StringBuilder markdown = new StringBuilder("# Online agent evaluation\n\n")
                .append("- Generated: `").append(report.generatedAt()).append("`\n")
                .append("- Git commit: `").append(report.gitCommit()).append("`\n")
                .append("- Runtime: Java `").append(report.javaVersion()).append("` on `")
                .append(report.operatingSystem()).append("`\n")
                .append("- Model: `").append(report.model()).append("`\n")
                .append("- Completion: **").append(report.passed()).append('/').append(report.total())
                .append(" (").append(String.format(Locale.ROOT, "%.1f%%", report.completionRate() * 100)).append(")**\n\n")
                .append("| ID | Category | Result | Rounds | Tool calls | Time | Estimated tokens | Verification |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---|\n");
        for (var result : report.results()) {
            markdown.append('|').append(result.id()).append('|').append(result.category()).append('|')
                    .append(result.passed() ? "PASS" : "FAIL").append('|').append(result.rounds()).append('|')
                    .append(result.toolCalls()).append('|').append(result.durationMs()).append(" ms|")
                    .append(result.estimatedTokens()).append('|').append(escape(result.verification())).append("|\n");
        }
        Files.writeString(results.resolve("online-latest.md"), markdown, StandardCharsets.UTF_8);
    }

    private static void write(Path workspace, String relative, String content) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static boolean contains(Path file, String expected) throws IOException {
        return Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).contains(expected);
    }

    private static boolean called(List<AgentEvent> events, String tool) {
        return events.stream().anyMatch(event -> event.type() == AgentEvent.Type.TOOL_CALL && event.name().equals(tool));
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replaceAll("\\s+", " ").strip();
    }

    private static String gitCommit(Path root) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return "unknown";
            }
            return new String(process.getInputStream().readNBytes(128), StandardCharsets.UTF_8).strip();
        } catch (Exception failure) {
            return "unknown";
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Verification(boolean passed, String detail) {
    }
}
