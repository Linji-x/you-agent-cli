package dev.youagent.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.plan.ExecutionPlan;
import dev.youagent.plan.ExecutionReport;
import dev.youagent.plan.PlanTask;
import dev.youagent.plan.ToolPlanExecutor;
import dev.youagent.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DemoRunner {
    private final ObjectMapper mapper = new ObjectMapper();

    public String run() throws IOException {
        Path sandbox = Files.createTempDirectory("you-agent-demo-");
        try {
            ToolRegistry tools = ToolRegistry.standard(sandbox, Duration.ofSeconds(10));
            List<PlanTask> tasks = plan();
            ExecutionPlan plan = new ExecutionPlan(tasks);

            StringBuilder transcript = new StringBuilder();
            transcript.append("INPUT  Create a Java greeting file, then verify its content.\n");
            transcript.append("PLAN\n");
            for (PlanTask task : tasks) {
                transcript.append("  ").append(task.id()).append(" <- ")
                        .append(task.dependsOn().isEmpty() ? "[]" : task.dependsOn())
                        .append(" : ").append(task.description()).append('\n');
            }
            transcript.append("TOOLS\n");
            ExecutionReport report = new ToolPlanExecutor(tools).execute(plan);
            for (PlanTask task : tasks) {
                String output = report.outputs().getOrDefault(task.id(), "blocked");
                transcript.append("  ").append(task.toolName()).append(" -> ")
                        .append(report.statuses().get(task.id())).append(" : ")
                        .append(oneLine(output)).append('\n');
            }
            transcript.append("RESULT ")
                    .append(report.succeeded() ? "SUCCESS" : "FAILED")
                    .append("; verified demo-output/Hello.java\n");
            return transcript.toString();
        } finally {
            deleteTree(sandbox);
        }
    }

    private List<PlanTask> plan() {
        ObjectNode inspect = mapper.createObjectNode().put("path", ".");
        ObjectNode write = mapper.createObjectNode()
                .put("path", "demo-output/Hello.java")
                .put("content", "public final class Hello { public static String greet() { return \"hello\"; } }\n");
        ObjectNode verify = mapper.createObjectNode().put("path", "demo-output/Hello.java");
        return List.of(
                new PlanTask("inspect", "Inspect the sandbox", List.of(), "list_directory", inspect),
                new PlanTask("create", "Create the requested Java source", List.of("inspect"), "write_file", write),
                new PlanTask("verify", "Read the created source as verification", List.of("create"), "read_file", verify)
        );
    }

    private static String oneLine(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "…";
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        IOException failure = null;
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException problem) {
                failure = problem;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
