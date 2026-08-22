package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class WriteFileTool implements Tool {
    @Override public String name() { return "write_file"; }
    @Override public String description() { return "Create or replace a UTF-8 text file inside the workspace."; }
    @Override public ObjectNode inputSchema() {
        ObjectNode schema = Schema.requiredString(Schema.object(), "path", "Workspace-relative file path");
        return Schema.requiredString(schema, "content", "Complete UTF-8 file content");
    }

    @Override
    public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
        Path file = new WorkspaceGuard(context.workspace()).resolve(arguments.path("path").asText());
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String content = arguments.path("content").asText();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return ToolExecution.success("wrote " + context.workspace().relativize(file) + " (" + content.length() + " chars)");
    }
}
