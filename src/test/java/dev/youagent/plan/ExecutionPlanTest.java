package dev.youagent.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanTest {
    @Test
    void executesInTopologicalOrder() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                task("compile", List.of("generate")),
                task("inspect", List.of()),
                task("generate", List.of("inspect"))
        ));
        List<String> order = new ArrayList<>();

        ExecutionReport report = new DagExecutor().execute(plan, task -> {
            order.add(task.id());
            return TaskOutcome.success(task.id() + " ok");
        });

        assertEquals(List.of("inspect", "generate", "compile"), order);
        assertTrue(report.succeeded());
    }

    @Test
    void propagatesFailureToDependentsButRunsIndependentBranch() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                task("root", List.of()),
                task("dependent", List.of("root")),
                task("independent", List.of())
        ));

        ExecutionReport report = new DagExecutor().execute(plan,
                task -> task.id().equals("root") ? TaskOutcome.failure("boom") : TaskOutcome.success("ok"));

        assertEquals(TaskStatus.FAILED, report.statuses().get("root"));
        assertEquals(TaskStatus.BLOCKED, report.statuses().get("dependent"));
        assertEquals(TaskStatus.SUCCEEDED, report.statuses().get("independent"));
        assertFalse(report.succeeded());
    }

    @Test
    void supportsSupplementalPlanningAndRejectsCycles() {
        ExecutionPlan plan = new ExecutionPlan(List.of(task("inspect", List.of())));
        plan.append(List.of(task("fix", List.of("inspect"))));
        assertEquals(List.of("inspect"), plan.readyTasks().stream().map(PlanTask::id).toList());

        assertThrows(IllegalArgumentException.class, () -> new ExecutionPlan(List.of(
                task("a", List.of("b")), task("b", List.of("a"))
        )));
    }

    private static PlanTask task(String id, List<String> dependencies) {
        return new PlanTask(id, id, dependencies, "", null);
    }
}
