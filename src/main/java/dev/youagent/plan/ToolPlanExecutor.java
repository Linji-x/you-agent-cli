package dev.youagent.plan;

import dev.youagent.llm.ToolCall;
import dev.youagent.tool.ToolExecution;
import dev.youagent.tool.ToolRegistry;

public final class ToolPlanExecutor {
    private final ToolRegistry tools;

    public ToolPlanExecutor(ToolRegistry tools) {
        this.tools = tools;
    }

    public ExecutionReport execute(ExecutionPlan plan) {
        return new DagExecutor().execute(plan, task -> {
            if (task.toolName().isBlank()) {
                return TaskOutcome.failure("task has no tool binding");
            }
            ToolExecution result = tools.execute(new ToolCall("plan-" + task.id(), task.toolName(), task.arguments()));
            return result.success() ? TaskOutcome.success(result.output()) : TaskOutcome.failure(result.toModelText());
        });
    }
}
