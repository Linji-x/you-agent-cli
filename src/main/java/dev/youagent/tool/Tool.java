package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {
    String name();

    String description();

    ObjectNode inputSchema();

    ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception;
}
