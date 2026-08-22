package dev.youagent.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class DagExecutor {
    @FunctionalInterface
    public interface TaskHandler {
        TaskOutcome execute(PlanTask task, Map<String, String> directDependencyOutputs) throws Exception;
    }

    @FunctionalInterface
    public interface SimpleTaskHandler {
        TaskOutcome execute(PlanTask task) throws Exception;
    }

    private final int maxParallelism;

    public DagExecutor() {
        this(1);
    }

    public DagExecutor(int maxParallelism) {
        if (maxParallelism < 1 || maxParallelism > 32) {
            throw new IllegalArgumentException("maxParallelism must be between 1 and 32");
        }
        this.maxParallelism = maxParallelism;
    }

    public ExecutionReport execute(ExecutionPlan plan, SimpleTaskHandler handler) {
        return execute(plan, (task, ignored) -> handler.execute(task));
    }

    public ExecutionReport execute(ExecutionPlan plan, TaskHandler handler) {
        Map<String, String> outputs = new LinkedHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(maxParallelism, runnable -> {
            Thread thread = new Thread(runnable, "you-agent-dag-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            while (!plan.terminal()) {
                List<PlanTask> ready = plan.readyTasks();
                if (ready.isEmpty()) {
                    throw new IllegalStateException("plan has no ready tasks but is not terminal");
                }
                Set<String> handled = new LinkedHashSet<>();
                for (PlanTask task : ready) {
                    if (handled.contains(task.id())) {
                        continue;
                    }
                    if (!task.parallelSafe() || maxParallelism == 1) {
                        runOne(plan, task, handler, outputs);
                        handled.add(task.id());
                        continue;
                    }
                    List<PlanTask> batch = compatibleParallelBatch(ready, handled, task);
                    runParallel(plan, batch, handler, outputs, executor);
                    batch.forEach(item -> handled.add(item.id()));
                }
            }
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Map<String, String> deterministic = new LinkedHashMap<>();
        plan.statuses().keySet().forEach(id -> {
            if (outputs.containsKey(id)) {
                deterministic.put(id, outputs.get(id));
            }
        });
        return new ExecutionReport(plan.statuses(), deterministic);
    }

    private static List<PlanTask> compatibleParallelBatch(List<PlanTask> ready, Set<String> handled,
                                                           PlanTask first) {
        List<PlanTask> batch = new ArrayList<>();
        Set<String> resources = new LinkedHashSet<>();
        batch.add(first);
        resources.addAll(first.exclusiveResources());
        for (PlanTask candidate : ready) {
            if (candidate.id().equals(first.id()) || handled.contains(candidate.id()) || !candidate.parallelSafe()) {
                continue;
            }
            if (candidate.exclusiveResources().stream().anyMatch(resources::contains)) {
                continue;
            }
            batch.add(candidate);
            resources.addAll(candidate.exclusiveResources());
        }
        return batch;
    }

    private static void runParallel(ExecutionPlan plan, List<PlanTask> batch, TaskHandler handler,
                                    Map<String, String> outputs, ExecutorService executor) {
        batch.forEach(task -> plan.markRunning(task.id()));
        List<Map<String, String>> contexts = batch.stream()
                .map(task -> dependencyOutputs(task, outputs))
                .toList();
        List<Future<TaskOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            PlanTask task = batch.get(i);
            Map<String, String> context = contexts.get(i);
            futures.add(executor.submit(() -> invoke(handler, task, context)));
        }
        for (int i = 0; i < batch.size(); i++) {
            PlanTask task = batch.get(i);
            TaskOutcome outcome;
            try {
                outcome = futures.get(i).get();
            } catch (Exception failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                outcome = TaskOutcome.failure(safeMessage(cause));
            }
            finish(plan, task, outcome, outputs);
        }
    }

    private static void runOne(ExecutionPlan plan, PlanTask task, TaskHandler handler,
                               Map<String, String> outputs) {
        plan.markRunning(task.id());
        finish(plan, task, invoke(handler, task, dependencyOutputs(task, outputs)), outputs);
    }

    private static TaskOutcome invoke(TaskHandler handler, PlanTask task, Map<String, String> dependencies) {
        try {
            TaskOutcome outcome = handler.execute(task, dependencies);
            return outcome == null ? TaskOutcome.failure("task handler returned null") : outcome;
        } catch (Exception failure) {
            return TaskOutcome.failure(safeMessage(failure));
        }
    }

    private static void finish(ExecutionPlan plan, PlanTask task, TaskOutcome outcome,
                               Map<String, String> outputs) {
        outputs.put(task.id(), outcome.output());
        if (outcome.success()) {
            plan.markSucceeded(task.id());
        } else {
            plan.markFailed(task.id());
        }
    }

    private static Map<String, String> dependencyOutputs(PlanTask task, Map<String, String> outputs) {
        Map<String, String> direct = new LinkedHashMap<>();
        task.dependsOn().forEach(id -> direct.put(id, outputs.getOrDefault(id, "")));
        return Collections.unmodifiableMap(direct);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
