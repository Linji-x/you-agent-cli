package dev.youagent.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolDefinition(String name, String description, ObjectNode inputSchema) {
}
