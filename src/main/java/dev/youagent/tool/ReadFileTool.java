package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ReadFileTool implements Tool {
    private static final int MAX_CHARS = 64_000;

    @Override public String name() { return "read_file"; }
    @Override public String description() { return "Read a UTF-8 text file inside the workspace."; }
    @Override public ObjectNode inputSchema() {
        return Schema.requiredString(Schema.object(), "path", "Workspace-relative file path");
    }

    @Override
    public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
        Path file = new WorkspaceGuard(context.workspace()).resolve(arguments.path("path").asText());
        if (!Files.isRegularFile(file)) {
            return ToolExecution.failure("NOT_FOUND", "File not found: " + context.workspace().relativize(file));
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n[truncated]";
        }
        return ToolExecution.success(text);
    }
}
