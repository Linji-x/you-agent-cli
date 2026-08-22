package dev.youagent.plan;

import java.util.List;

public record PlanRunResult(List<PlanTask> tasks, ExecutionReport report) {
    public PlanRunResult {
        tasks = List.copyOf(tasks);
    }
}
