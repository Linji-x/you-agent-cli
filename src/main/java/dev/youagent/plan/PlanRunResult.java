package dev.youagent.plan;

import dev.youagent.agent.AgentEvent;

import java.util.List;

public record PlanRunResult(List<PlanTask> tasks, ExecutionReport report, List<AgentEvent> events,
                            int rounds, int estimatedTokens) {
    public PlanRunResult {
        tasks = List.copyOf(tasks);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public PlanRunResult(List<PlanTask> tasks, ExecutionReport report) {
        this(tasks, report, List.of(), 0, 0);
    }
}
