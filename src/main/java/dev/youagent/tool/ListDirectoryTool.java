package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ListDirectoryTool implements Tool {
    @Override public String name() { return "list_directory"; }
    @Override public String description() { return "List direct children of a workspace directory."; }
    @Override public ObjectNode inputSchema() {
        return Schema.requiredString(Schema.object(), "path", "Workspace-relative directory; use . for root");
    }

    @Override
    public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
        Path directory = new WorkspaceGuard(context.workspace()).resolve(arguments.path("path").asText());
        if (!Files.isDirectory(directory)) {
            return ToolExecution.failure("NOT_FOUND", "Directory not found: " + context.workspace().relativize(directory));
        }
        try (var stream = Files.list(directory)) {
            List<String> entries = stream.sorted()
                    .limit(500)
                    .map(path -> (Files.isDirectory(path) ? "dir  " : "file ") + path.getFileName())
                    .toList();
            return ToolExecution.success(String.join("\n", entries));
        }
    }
}
