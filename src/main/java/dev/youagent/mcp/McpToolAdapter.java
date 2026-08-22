package dev.youagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.tool.Tool;
import dev.youagent.tool.ToolContext;
import dev.youagent.tool.ToolExecution;

import java.time.Duration;

public final class McpToolAdapter implements Tool {
    private final McpClient client;
    private final McpRemoteTool remote;
    private final Duration timeout;

    public McpToolAdapter(McpClient client, McpRemoteTool remote, Duration timeout) {
        this.client = client;
        this.remote = remote;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return ("mcp__" + client.serverName() + "__" + remote.name())
                .toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    @Override public String description() { return "MCP " + client.serverName() + ": " + remote.description(); }
    @Override public ObjectNode inputSchema() { return remote.inputSchema(); }

    @Override
    public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
        JsonNode result = client.callTool(remote.name(), arguments, timeout);
        boolean isError = result.path("isError").asBoolean(false);
        StringBuilder text = new StringBuilder();
        for (JsonNode content : result.path("content")) {
            if (content.path("type").asText().equals("text")) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(content.path("text").asText());
            }
        }
        String output = text.isEmpty() ? result.toString() : text.toString();
        return isError ? ToolExecution.failure("MCP_TOOL_ERROR", output) : ToolExecution.success(output);
    }
}
