package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.llm.ToolCall;
import dev.youagent.llm.ToolDefinition;
import dev.youagent.search.CodeIndexService;
import dev.youagent.search.CodeSearchTools;
import dev.youagent.search.EmbeddingModel;
import dev.youagent.search.FeatureHashEmbeddingModel;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolRegistry implements AutoCloseable {
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final ToolContext context;

    public ToolRegistry(ToolContext context) {
        this.context = context;
    }

    public static ToolRegistry standard(Path workspace, Duration commandTimeout) {
        Path root = workspace.toAbsolutePath().normalize();
        return standard(root, commandTimeout, root.resolve(".you-agent/code-index.db"),
                new FeatureHashEmbeddingModel(256));
    }

    public static ToolRegistry standard(Path workspace, Duration commandTimeout,
                                        Path indexFile, EmbeddingModel embeddingModel) {
        ToolRegistry registry = new ToolRegistry(new ToolContext(workspace, commandTimeout));
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new ListDirectoryTool());
        registry.register(new GlobFilesTool());
        registry.register(new GrepCodeTool());
        registry.register(new ExecuteCommandTool());
        CodeSearchTools.register(registry, new CodeIndexService(workspace, indexFile, embeddingModel));
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

    public synchronized void registerResource(AutoCloseable resource) {
        resources.add(resource);
    }

    @Override
    public synchronized void close() throws Exception {
        Exception first = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception failure) {
                if (first == null) {
                    first = failure;
                }
            }
        }
        resources.clear();
        if (first != null) {
            throw first;
        }
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
        if (arguments == null) {
            return "$ must not be null";
        }
        return validateNode(arguments, schema, "$");
    }

    private String validateNode(JsonNode value, JsonNode schema, String path) {
        String type = schema.path("type").asText("");
        if (!type.isBlank() && !matchesType(value, type)) {
            return path + " must be " + type;
        }
        if (schema.path("enum").isArray()) {
            boolean matched = false;
            for (JsonNode allowed : schema.path("enum")) {
                if (allowed.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return path + " must match one of the allowed enum values";
            }
        }
        if (value.isObject() && (type.equals("object") || schema.has("properties") || schema.has("required"))) {
            List<String> missing = new ArrayList<>();
            for (JsonNode required : schema.path("required")) {
                String name = required.asText();
                if (!value.has(name) || value.path(name).isNull()) {
                    missing.add(name);
                }
            }
            if (!missing.isEmpty()) {
                return path + " missing required fields: " + String.join(", ", missing);
            }
            JsonNode properties = schema.path("properties");
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode expected = properties.path(field.getKey());
                String childPath = path + "." + field.getKey();
                if (expected.isMissingNode()) {
                    JsonNode additional = schema.path("additionalProperties");
                    if (additional.isBoolean() && !additional.asBoolean()) {
                        return childPath + " is not allowed";
                    }
                    if (additional.isObject()) {
                        String nested = validateNode(field.getValue(), additional, childPath);
                        if (nested != null) {
                            return nested;
                        }
                    }
                    continue;
                }
                String nested = validateNode(field.getValue(), expected, childPath);
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (value.isArray() && schema.path("items").isObject()) {
            int index = 0;
            for (JsonNode item : value) {
                String nested = validateNode(item, schema.path("items"), path + "[" + index + "]");
                if (nested != null) {
                    return nested;
                }
                index++;
            }
        }
        return null;
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }
}
