package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

final class GlobFilesTool implements Tool {
    @Override public String name() { return "glob_files"; }
    @Override public String description() { return "Find files by a Java glob expression, for example **/*.java."; }
    @Override public ObjectNode inputSchema() {
        ObjectNode schema = Schema.requiredString(Schema.object(), "pattern", "Glob expression");
        return Schema.optionalInteger(schema, "max_results", "Maximum returned paths", 100);
    }

    @Override
    public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
        String pattern = arguments.path("pattern").asText();
        int max = Math.max(1, Math.min(arguments.path("max_results").asInt(100), 500));
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (var stream = Files.walk(context.workspace())) {
            List<String> matches = stream.filter(Files::isRegularFile)
                    .map(context.workspace()::relativize)
                    .filter(matcher::matches)
                    .sorted()
                    .limit(max)
                    .map(Path::toString)
                    .toList();
            return ToolExecution.success(String.join("\n", matches));
        }
    }
}
