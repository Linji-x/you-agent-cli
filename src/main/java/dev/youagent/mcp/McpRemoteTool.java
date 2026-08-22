package dev.youagent.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record McpRemoteTool(String name, String description, ObjectNode inputSchema) {
}
