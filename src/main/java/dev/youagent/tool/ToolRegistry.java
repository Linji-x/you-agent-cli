package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.llm.ToolCall;
import dev.youagent.llm.ToolDefinition;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ToolContext context;

    public ToolRegistry(ToolContext context) {
        this.context = context;
    }

    public static ToolRegistry standard(Path workspace, Duration commandTimeout) {
        ToolRegistry registry = new ToolRegistry(new ToolContext(workspace, commandTimeout));
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new ListDirectoryTool());
        registry.register(new GlobFilesTool());
        registry.register(new GrepCodeTool());
        registry.register(new ExecuteCommandTool());
        return registry;
    }

    public synchronized void register(Tool tool) {
        if (tool.name() == null || !tool.name().matches("[a-z][a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("invalid tool name");
        }
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalArgumentException("duplicate tool: " + tool.name());
        }
    }

    public synchronized List<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }

    public synchronized Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public ToolExecution execute(ToolCall call) {
        Tool tool;
        synchronized (this) {
            tool = tools.get(call.name());
        }
        if (tool == null) {
            return ToolExecution.failure("UNKNOWN_TOOL", "Unknown tool: " + call.name());
        }
        String validation = validate(call.arguments(), tool.inputSchema());
        if (validation != null) {
            return ToolExecution.failure("INVALID_ARGUMENTS", validation);
        }
        try {
            return tool.execute(call.arguments(), context);
        } catch (SecurityException denied) {
            return ToolExecution.failure("POLICY_DENIED", denied.getMessage());
        } catch (IllegalArgumentException invalid) {
            return ToolExecution.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return ToolExecution.failure("TOOL_FAILED", message);
        }
    }

    private String validate(JsonNode arguments, ObjectNode schema) {
        if (arguments == null || !arguments.isObject()) {
            return "arguments must be a JSON object";
        }
        List<String> missing = new ArrayList<>();
        for (JsonNode required : schema.path("required")) {
            String name = required.asText();
            if (!arguments.has(name) || arguments.path(name).isNull()) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            return "missing required fields: " + String.join(", ", missing);
        }
        JsonNode properties = schema.path("properties");
        var fields = arguments.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode expected = properties.path(field.getKey());
            if (expected.isMissingNode()) {
                return "unknown field: " + field.getKey();
            }
            String type = expected.path("type").asText();
            boolean valid = switch (type) {
                case "string" -> field.getValue().isTextual();
                case "integer" -> field.getValue().isIntegralNumber();
                case "array" -> field.getValue().isArray();
                case "boolean" -> field.getValue().isBoolean();
                default -> true;
            };
            if (!valid) {
                return "field '" + field.getKey() + "' must be " + type;
            }
        }
        return null;
    }
}
