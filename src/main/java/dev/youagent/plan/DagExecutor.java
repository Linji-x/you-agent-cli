package dev.youagent.plan;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DagExecutor {
    @FunctionalInterface
    public interface TaskHandler {
        TaskOutcome execute(PlanTask task) throws Exception;
    }

    public ExecutionReport execute(ExecutionPlan plan, TaskHandler handler) {
        Map<String, String> outputs = new LinkedHashMap<>();
        while (!plan.terminal()) {
            var ready = plan.readyTasks();
            if (ready.isEmpty()) {
                throw new IllegalStateException("plan has no ready tasks but is not terminal");
            }
            for (PlanTask task : ready) {
                plan.markRunning(task.id());
                try {
                    TaskOutcome outcome = handler.execute(task);
                    outputs.put(task.id(), outcome.output());
                    if (outcome.success()) {
                        plan.markSucceeded(task.id());
                    } else {
                        plan.markFailed(task.id());
                    }
                } catch (Exception failure) {
                    String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                    outputs.put(task.id(), message);
                    plan.markFailed(task.id());
                }
            }
        }
        return new ExecutionReport(plan.statuses(), outputs);
    }
}
