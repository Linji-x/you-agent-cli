package dev.youagent.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void passesDirectDependencyOutputsIntoConvergingNode() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                task("left", List.of()),
                task("right", List.of()),
                task("join", List.of("left", "right"))
        ));
        List<Map<String, String>> observed = new ArrayList<>();

        ExecutionReport report = new DagExecutor().execute(plan, (task, dependencies) -> {
            if (task.id().equals("join")) {
                observed.add(dependencies);
            }
            return TaskOutcome.success(task.id() + "-output");
        });

        assertEquals(Map.of("left", "left-output", "right", "right-output"), observed.get(0));
        assertEquals(List.of("left", "right", "join"), report.outputs().keySet().stream().toList());
    }

    @Test
    void explicitlySafeIndependentTasksRunInParallel() {
        ExecutionPlan plan = new ExecutionPlan(List.of(
                parallelTask("left", List.of(), List.of("left.txt")),
                parallelTask("right", List.of(), List.of("right.txt"))
        ));
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        ExecutionReport report = new DagExecutor(2).execute(plan, (task, dependencies) -> {
            int now = active.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            bothStarted.countDown();
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            active.decrementAndGet();
            return TaskOutcome.success(task.id());
        });

        assertTrue(report.succeeded());
        assertEquals(2, maximum.get());
        assertEquals(List.of("left", "right"), report.outputs().keySet().stream().toList());
    }

    @Test
    void unsafeOrResourceConflictingTasksStaySerial() {
        assertSerial(List.of(task("a", List.of()), task("b", List.of())));
        assertSerial(List.of(
                parallelTask("a", List.of(), List.of("shared.txt")),
                parallelTask("b", List.of(), List.of("shared.txt"))
        ));
    }

    private static void assertSerial(List<PlanTask> tasks) {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        ExecutionReport report = new DagExecutor(4).execute(new ExecutionPlan(tasks), (task, dependencies) -> {
            int now = active.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            Thread.sleep(40);
            active.decrementAndGet();
            return TaskOutcome.success(task.id());
        });
        assertTrue(report.succeeded());
        assertEquals(1, maximum.get());
    }

    private static PlanTask task(String id, List<String> dependencies) {
        return new PlanTask(id, id, dependencies, "", null);
    }

    private static PlanTask parallelTask(String id, List<String> dependencies, List<String> resources) {
        return new PlanTask(id, id, dependencies, "", null, true, resources);
    }
}
