package dev.youagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;

public record PlanTask(
        String id,
        String description,
        List<String> dependsOn,
        String toolName,
        JsonNode arguments,
        boolean parallelSafe,
        List<String> exclusiveResources
) {
    public PlanTask {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid task id: " + id);
        }
        description = description == null ? "" : description;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        toolName = toolName == null ? "" : toolName;
        arguments = arguments == null ? JsonNodeFactory.instance.objectNode() : arguments;
        exclusiveResources = exclusiveResources == null ? List.of() : exclusiveResources.stream()
                .map(resource -> resource == null ? "" : resource.strip().toLowerCase())
                .filter(resource -> !resource.isBlank())
                .distinct()
                .toList();
    }

    public PlanTask(String id, String description, List<String> dependsOn,
                    String toolName, JsonNode arguments) {
        this(id, description, dependsOn, toolName, arguments, false, List.of());
    }

    public PlanTask(String id, String description, List<String> dependsOn,
                    String toolName, JsonNode arguments, boolean parallelSafe) {
        this(id, description, dependsOn, toolName, arguments, parallelSafe, List.of());
    }
}
