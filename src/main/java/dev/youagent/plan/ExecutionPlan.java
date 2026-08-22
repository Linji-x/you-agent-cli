package dev.youagent.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExecutionPlan {
    private final Map<String, PlanTask> tasks = new LinkedHashMap<>();
    private final Map<String, TaskStatus> statuses = new LinkedHashMap<>();

    public ExecutionPlan(Collection<PlanTask> initialTasks) {
        append(initialTasks);
    }

    public synchronized void append(Collection<PlanTask> supplementalTasks) {
        Map<String, PlanTask> candidate = new LinkedHashMap<>(tasks);
        for (PlanTask task : supplementalTasks) {
            if (candidate.putIfAbsent(task.id(), task) != null) {
                throw new IllegalArgumentException("duplicate task id: " + task.id());
            }
        }
        validate(candidate);
        for (PlanTask task : supplementalTasks) {
            tasks.put(task.id(), task);
            statuses.put(task.id(), TaskStatus.WAITING);
        }
    }

    public synchronized List<PlanTask> readyTasks() {
        List<PlanTask> ready = new ArrayList<>();
        for (PlanTask task : tasks.values()) {
            if (statuses.get(task.id()) != TaskStatus.WAITING) {
                continue;
            }
            boolean dependenciesSucceeded = task.dependsOn().stream()
                    .allMatch(dependency -> statuses.get(dependency) == TaskStatus.SUCCEEDED);
            if (dependenciesSucceeded) {
                ready.add(task);
            }
        }
        return ready;
    }

    public synchronized void markRunning(String id) {
        transition(id, TaskStatus.WAITING, TaskStatus.RUNNING);
    }

    public synchronized void markSucceeded(String id) {
        transition(id, TaskStatus.RUNNING, TaskStatus.SUCCEEDED);
    }

    public synchronized void markFailed(String id) {
        transition(id, TaskStatus.RUNNING, TaskStatus.FAILED);
        propagateBlocked();
    }

    public synchronized boolean terminal() {
        return statuses.values().stream().noneMatch(status -> status == TaskStatus.WAITING || status == TaskStatus.RUNNING);
    }

    public synchronized Map<String, TaskStatus> statuses() {
        return new LinkedHashMap<>(statuses);
    }

    private void propagateBlocked() {
        boolean changed;
        do {
            changed = false;
            for (PlanTask task : tasks.values()) {
                if (statuses.get(task.id()) != TaskStatus.WAITING) {
                    continue;
                }
                boolean blocked = task.dependsOn().stream()
                        .map(statuses::get)
                        .anyMatch(status -> status == TaskStatus.FAILED || status == TaskStatus.BLOCKED);
                if (blocked) {
                    statuses.put(task.id(), TaskStatus.BLOCKED);
                    changed = true;
                }
            }
        } while (changed);
    }

    private void transition(String id, TaskStatus expected, TaskStatus next) {
        TaskStatus current = statuses.get(id);
        if (current == null) {
            throw new IllegalArgumentException("unknown task: " + id);
        }
        if (current != expected) {
            throw new IllegalStateException("task " + id + " is " + current + ", expected " + expected);
        }
        statuses.put(id, next);
    }

    private static void validate(Map<String, PlanTask> candidate) {
        for (PlanTask task : candidate.values()) {
            for (String dependency : task.dependsOn()) {
                if (!candidate.containsKey(dependency)) {
                    throw new IllegalArgumentException("task " + task.id() + " has unknown dependency " + dependency);
                }
                if (dependency.equals(task.id())) {
                    throw new IllegalArgumentException("task cannot depend on itself: " + task.id());
                }
            }
        }
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String id : candidate.keySet()) {
            visit(id, candidate, visiting, visited, path);
        }
    }

    private static void visit(String id, Map<String, PlanTask> tasks, Set<String> visiting,
                              Set<String> visited, Deque<String> path) {
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            path.addLast(id);
            throw new IllegalArgumentException("cycle detected: " + String.join(" -> ", path));
        }
        path.addLast(id);
        for (String dependency : tasks.get(id).dependsOn()) {
            visit(dependency, tasks, visiting, visited, path);
        }
        path.removeLast();
        visiting.remove(id);
        visited.add(id);
    }
}
