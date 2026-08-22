package dev.youagent.plan;

import java.util.LinkedHashMap;
import java.util.Map;

public record ExecutionReport(Map<String, TaskStatus> statuses, Map<String, String> outputs) {
    public ExecutionReport {
        statuses = Map.copyOf(new LinkedHashMap<>(statuses));
        outputs = Map.copyOf(new LinkedHashMap<>(outputs));
    }

    public boolean succeeded() {
        return statuses.values().stream().allMatch(status -> status == TaskStatus.SUCCEEDED);
    }
}
